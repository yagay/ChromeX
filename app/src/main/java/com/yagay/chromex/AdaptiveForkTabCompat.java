package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extra tab compatibility for vendor Chromium forks whose tab creator has been R8-obfuscated.
 * The class deliberately avoids package/version-specific short names: creator methods are located
 * by their stable parameter/return types (LoadUrlParams -> Tab) from live activity fields.
 */
final class AdaptiveForkTabCompat {
    private static final String LOAD_URL_PARAMS =
            "org.chromium.content_public.browser.LoadUrlParams";
    private static final String TAB = "org.chromium.chrome.browser.tab.Tab";

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Method homepageGetter;
    private final Set<String> hookedCreators = ConcurrentHashMap.newKeySet();
    private final Set<Activity> scheduled = Collections.newSetFromMap(new IdentityHashMap<>());

    AdaptiveForkTabCompat(ChromiumProfile profile, ChromeRuntime runtime,
                          HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.loader = runtime.classLoader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.homepageGetter = AdaptiveDexResolver.resolveHomepageGetter(runtime, hooks);
    }

    void install() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:adaptive:fork-tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) {
                        Activity activity = (Activity) receiver;
                        installCreators(activity);
                        scheduleColdStart(activity);
                    }
                    return result;
                });
    }

    private void installCreators(Activity activity) {
        forEachActivityField(activity, (owner, candidate) -> {
            Class<?> c = candidate.getClass();
            while (c != null && c != Object.class) {
                for (Method method : c.getDeclaredMethods()) {
                    if (!isCreator(method)) continue;
                    String key = method.toGenericString();
                    if (!hookedCreators.add(key)) continue;
                    try { method.setAccessible(true); } catch (Throwable ignored) {}
                    hooks.method(method, "chromex:adaptive:fork-tabs:creator:" + hookedCreators.size(),
                            chain -> {
                                if (!Config.get(prefs, Config.NEWTAB_HOME)) return chain.proceed();
                                Object home = homepageGurl(false);
                                String wanted = gurlText(home);
                                if (home == null || wanted == null || wanted.isBlank() || isNtp(wanted)) {
                                    return chain.proceed();
                                }
                                Object params = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                                if (!rewriteLoadParams(params, home, wanted)) return chain.proceed();
                                hooks.info(profile.label() + " fork new-tab redirected through "
                                        + method.getDeclaringClass().getName() + "#" + method.getName()
                                        + " -> " + wanted);
                                return chain.proceed();
                            });
                    hooks.info("adaptive fork tab creator resolved: "
                            + method.getDeclaringClass().getName() + "#" + method.getName());
                }
                c = c.getSuperclass();
            }
        });
    }

    private void scheduleColdStart(Activity activity) {
        if (!Config.get(prefs, Config.CLEAN_START)) return;
        Intent intent = activity.getIntent();
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getData() != null) {
            return;
        }
        synchronized (scheduled) {
            if (!scheduled.add(activity)) return;
        }
        main.postDelayed(() -> coldRound(activity, 0), Math.max(900L, profile.coldDelayMs));
    }

    private void coldRound(Activity activity, int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            installCreators(activity);
            Object model = model(activity, false);
            Object home = homepageGurl(true);
            String wanted = gurlText(home);
            if (model == null || home == null || wanted == null || wanted.isBlank()) {
                retry(activity, round, "model/home unavailable");
                return;
            }

            Object keep = findTab(model, wanted);
            if (keep == null) keep = openViaStructuralCreator(activity, home);
            if (keep == null) {
                retry(activity, round, "creator unavailable");
                return;
            }

            TabCloseStrategy.closeExcept(loader, model, keep, hooks, false);
            Object incognito = model(activity, true);
            if (incognito != null && incognito != model) {
                TabCloseStrategy.closeAll(loader, incognito, hooks, false);
            }
            hooks.info(profile.label() + " fork cold start settled at round " + round
                    + " tabs=" + count(model) + " homepage=" + wanted);
        } catch (Throwable t) {
            hooks.warn("adaptive fork cold-start round " + round + " failed: "
                    + t.getClass().getSimpleName());
            retry(activity, round, t.getClass().getSimpleName());
        }
    }

    private void retry(Activity activity, int round, String reason) {
        if (round + 1 >= profile.maxRounds) {
            hooks.warn("adaptive fork cold start unresolved after " + profile.maxRounds
                    + " rounds: " + reason);
            return;
        }
        main.postDelayed(() -> coldRound(activity, round + 1), profile.retryDelayMs);
    }

    private Object openViaStructuralCreator(Activity activity, Object home) {
        final Object[] opened = new Object[1];
        forEachActivityField(activity, (owner, candidate) -> {
            if (opened[0] != null) return;
            Class<?> c = candidate.getClass();
            while (c != null && c != Object.class && opened[0] == null) {
                for (Method method : c.getDeclaredMethods()) {
                    if (!isCreator(method)) continue;
                    try {
                        Object load = buildLoadParams(home);
                        if (load == null) continue;
                        Object[] args = buildArgs(method.getParameterTypes(), load);
                        method.setAccessible(true);
                        Object value = method.invoke(candidate, args);
                        if (value != null) {
                            opened[0] = value;
                            hooks.info("adaptive home tab opened via structural creator "
                                    + method.getDeclaringClass().getName() + "#" + method.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        });
        return opened[0];
    }

    private Object buildLoadParams(Object home) {
        try {
            Class<?> type = Reflect.cls(loader, LOAD_URL_PARAMS);
            return Reflect.construct(type, home);
        } catch (Throwable ignored) {
            String spec = gurlText(home);
            if (spec == null) return null;
            try {
                Class<?> type = Reflect.cls(loader, LOAD_URL_PARAMS);
                return Reflect.construct(type, spec, 0);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private Object[] buildArgs(Class<?>[] types, Object load) {
        Object[] args = new Object[types.length];
        args[0] = load;
        boolean launchTypeAssigned = false;
        for (int i = 1; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == boolean.class) args[i] = Boolean.FALSE;
            else if (type == int.class) {
                // Chromium TabLaunchType.FROM_CHROME_UI has historically been 2; only the first
                // int receives it. Remaining numeric bookkeeping arguments use zero.
                args[i] = launchTypeAssigned ? 0 : 2;
                launchTypeAssigned = true;
            } else if (type == long.class) args[i] = 0L;
            else if (type == float.class) args[i] = 0f;
            else if (type == double.class) args[i] = 0d;
            else if (type == short.class) args[i] = (short) 0;
            else if (type == byte.class) args[i] = (byte) 0;
            else if (type == char.class) args[i] = (char) 0;
            else args[i] = null;
        }
        return args;
    }

    private boolean rewriteLoadParams(Object params, Object home, String wanted) {
        if (params == null) return false;
        boolean changed = false;
        Class<?> c = params.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(params);
                    if (value instanceof String && isNtp((String) value)) {
                        field.set(params, wanted);
                        changed = true;
                    } else if (value != null && Chrome145.GURL.equals(value.getClass().getName())
                            && isNtp(gurlText(value))) {
                        field.set(params, home);
                        changed = true;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return changed;
    }

    private boolean isCreator(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())) return false;
        if (!TAB.equals(method.getReturnType().getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length > 0 && LOAD_URL_PARAMS.equals(p[0].getName());
    }

    private Object model(Activity activity, boolean incognito) {
        try {
            Class<?> tabModelApi = Reflect.cls(loader, Chrome145.TAB_MODEL_API);
            final Object[] result = new Object[1];
            forEachActivityField(activity, (owner, candidate) -> {
                if (result[0] != null) return;
                Class<?> c = candidate.getClass();
                while (c != null && c != Object.class && result[0] == null) {
                    for (Method method : c.getDeclaredMethods()) {
                        if (Modifier.isStatic(method.getModifiers())
                                || !tabModelApi.isAssignableFrom(method.getReturnType())) continue;
                        Class<?>[] p = method.getParameterTypes();
                        if (p.length != 1 || p[0] != boolean.class) continue;
                        try {
                            method.setAccessible(true);
                            Object value = method.invoke(candidate, incognito);
                            if (value != null) result[0] = value;
                        } catch (Throwable ignored) {}
                    }
                    c = c.getSuperclass();
                }
            });
            return result[0];
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findTab(Object model, String wanted) {
        int total = count(model);
        for (int i = 0; i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String current = gurlText(Reflect.call(tab, "getUrl"));
                if (wanted.equals(current) || (isNtp(wanted) && isNtp(current))) return tab;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int count(Object model) {
        if (model == null) return 0;
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private Object tabAt(Object model, int index) {
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) { return null; }
    }

    private Object homepageGurl(boolean forZeroTabs) {
        if (homepageGetter == null) return null;
        try {
            Object owner = Modifier.isStatic(homepageGetter.getModifiers())
                    ? null : AdaptiveDexResolver.singletonOwner(homepageGetter);
            if (!Modifier.isStatic(homepageGetter.getModifiers()) && owner == null) return null;
            Class<?>[] p = homepageGetter.getParameterTypes();
            if (p.length == 0) return homepageGetter.invoke(owner);
            if (p.length == 1 && p[0] == boolean.class) return homepageGetter.invoke(owner, false);
            if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                return homepageGetter.invoke(owner, false, forZeroTabs);
            }
        } catch (Throwable t) {
            hooks.warn("adaptive fork homepage invocation failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String name : new String[]{"getSpec", "j", "n", "e", "g"}) {
            try {
                Object value = Reflect.call(gurl, name);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        Class<?> c = gurl.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(gurl);
                    if (value instanceof String && ((String) value).contains("://")) {
                        return (String) value;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void forEachActivityField(Activity activity, FieldObjectConsumer consumer) {
        if (activity == null) return;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Class<?> c = activity.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value != null && seen.add(value)) consumer.accept(activity, value);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }

    private interface FieldObjectConsumer {
        void accept(Object owner, Object value);
    }
}
