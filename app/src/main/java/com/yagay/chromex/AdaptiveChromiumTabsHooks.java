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
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Structural tab/homepage hooks for unknown Chromium versions and vendor forks.
 *
 * <p>No R8 class/field names are assumed. The implementation relies on stable Chromium interfaces,
 * semantic method names that survive JNI generation, and DexKit-resolved homepage accessors.</p>
 */
final class AdaptiveChromiumTabsHooks {
    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());
    private final Method homepageGetter;

    AdaptiveChromiumTabsHooks(ChromiumProfile profile, ChromeRuntime runtime,
                              HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.loader = runtime.classLoader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.homepageGetter = AdaptiveDexResolver.resolveHomepageGetter(runtime, hooks);
    }

    void install() {
        installNoRestore();
        installLifecycle();
        installNewTabRedirects();
    }

    private void installNoRestore() {
        try {
            Class<?> command = Reflect.cls(loader, "org.chromium.base.CommandLine");
            Method candidate = null;
            for (Method method : command.getDeclaredMethods()) {
                if (method.getReturnType() != boolean.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                if (candidate != null) {
                    hooks.warn("adaptive no-restore ambiguous; preserving browser restore behavior");
                    return;
                }
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                candidate = method;
            }
            if (candidate == null) return;
            hooks.method(candidate, "chromex:adaptive:tabs:no-restore", chain -> {
                if (Config.get(prefs, Config.CLEAN_START)
                        && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                return chain.proceed();
            });
        } catch (Throwable t) {
            hooks.warn("adaptive no-restore unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void installLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:adaptive:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity && isChromeTabbedActivity((Activity) receiver)) {
                        scheduleColdStart((Activity) receiver);
                    }
                    return result;
                });

        hooks.exact(loader, Chrome145.ACTIVITY, "moveTaskToBack",
                new Class<?>[]{boolean.class}, "chromex:adaptive:tabs:exit-back", chain -> {
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity && isChromeTabbedActivity((Activity) receiver)
                            && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                        cleanupForExit((Activity) receiver, "moveTaskToBack");
                    }
                    return chain.proceed();
                });

        // Older forks can inherit onDestroy instead of declaring it on ChromeTabbedActivity.
        // HookSupport.exact walks superclasses; the receiver guard keeps the hook process-local.
        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex:adaptive:tabs:onDestroy", chain -> {
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) {
                        Activity activity = (Activity) receiver;
                        if (activity.isFinishing() && isChromeTabbedActivity(activity)
                                && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                            cleanupForExit(activity, "onDestroy");
                        }
                    }
                    return chain.proceed();
                });
    }

    private void installNewTabRedirects() {
        for (String method : new String[]{
                "openTabProgrammatically", "createNewTab", "createNewTabWithIndex", "openNewTab"}) {
            hooks.all(loader, Chrome145.TAB_MODEL, method,
                    "chromex:adaptive:tabs:newtab:" + method, chain -> {
                        if (!Config.get(prefs, Config.NEWTAB_HOME)) return chain.proceed();
                        Object home = homepageGurl(false);
                        String wanted = gurlText(home);
                        if (home == null || wanted == null || wanted.isBlank() || isNtp(wanted)) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        Class<?> gurlType;
                        try { gurlType = Reflect.cls(loader, Chrome145.GURL); }
                        catch (Throwable ignored) { return chain.proceed(); }
                        boolean changed = false;
                        for (int i = 0; i < args.length; i++) {
                            Object arg = args[i];
                            if (arg == null || !gurlType.isInstance(arg)) continue;
                            String current = gurlText(arg);
                            if (!isNtp(current)) continue;
                            args[i] = home;
                            changed = true;
                            break;
                        }
                        if (!changed) return chain.proceed();
                        hooks.info(profile.label() + " new-tab redirected through TabModelJniBridge#"
                                + method + " -> " + wanted);
                        return chain.proceed(args);
                    });
        }
    }

    private void scheduleColdStart(Activity activity) {
        if (!Config.get(prefs, Config.CLEAN_START)) return;
        Intent intent = activity.getIntent();
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getData() != null) {
            return;
        }
        synchronized (handled) {
            if (!handled.add(activity)) return;
        }
        main.postDelayed(() -> coldRound(activity, 0), profile.coldDelayMs);
    }

    private void coldRound(Activity activity, int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object regular = model(activity, false);
            if (regular == null) {
                retry(activity, round);
                return;
            }

            Object home = homepageGurl(true);
            String wanted = gurlText(home);
            if (home == null || wanted == null || wanted.isBlank()) {
                retry(activity, round);
                return;
            }

            Object keep = findTab(regular, wanted);
            if (keep == null) keep = openHomeTab(regular, home);
            if (keep == null) {
                retry(activity, round);
                return;
            }

            TabCloseStrategy.closeExcept(loader, regular, keep, hooks, false);
            Object incognito = model(activity, true);
            if (incognito != null && incognito != regular) {
                TabCloseStrategy.closeAll(loader, incognito, hooks, false);
            }

            int count = count(regular);
            if (count <= 1 || round + 1 >= profile.maxRounds) {
                hooks.info(profile.label() + " adaptive cold start settled at round " + round
                        + " tabs=" + count + " homepage=" + wanted);
            } else {
                retry(activity, round);
            }
        } catch (Throwable t) {
            hooks.error(profile.label() + " adaptive cold-start round " + round, t);
            retry(activity, round);
        }
    }

    private void retry(Activity activity, int round) {
        if (round + 1 >= profile.maxRounds) return;
        main.postDelayed(() -> coldRound(activity, round + 1), profile.retryDelayMs);
    }

    private void cleanupForExit(Activity activity, String reason) {
        try {
            Object regular = model(activity, false);
            Object incognito = model(activity, true);
            TabCloseStrategy.closeAll(loader, regular, hooks, true);
            if (incognito != null && incognito != regular) {
                TabCloseStrategy.closeAll(loader, incognito, hooks, true);
            }
            hooks.info(profile.label() + " adaptive exit tab cleanup via " + reason);
        } catch (Throwable t) {
            hooks.error(profile.label() + " adaptive exit cleanup via " + reason, t);
        }
    }

    private Object model(Activity activity, boolean incognito) {
        try {
            Class<?> stableSelector = Reflect.cls(loader,
                    "org.chromium.chrome.browser.tabmodel.TabModelSelector");
            Object selector = Reflect.findFieldValueByType(activity, stableSelector);
            Object value = selectStableModel(selector, incognito);
            if (value != null) return value;
        } catch (Throwable ignored) {}

        return structuralModel(activity, incognito);
    }

    private Object selectStableModel(Object selector, boolean incognito) {
        if (selector == null) return null;
        for (String method : new String[]{"getModel", "getTabModel"}) {
            try {
                Object value = Reflect.call(selector, method, incognito);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object structuralModel(Activity activity, boolean incognito) {
        try {
            Class<?> tabModelApi = Reflect.cls(loader, Chrome145.TAB_MODEL_API);
            Class<?> type = activity.getClass();
            while (type != null && type != Object.class) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Object candidate;
                    try {
                        field.setAccessible(true);
                        candidate = field.get(activity);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (candidate == null) continue;
                    Method selector = Reflect.signature(candidate.getClass(), tabModelApi,
                            boolean.class);
                    if (selector == null) continue;
                    try {
                        Object value = selector.invoke(candidate, incognito);
                        if (value != null) {
                            hooks.info("adaptive TabModel selector resolved: "
                                    + candidate.getClass().getName() + "#" + selector.getName());
                            return value;
                        }
                    } catch (Throwable ignored) {}
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object homepageGurl(boolean forZeroTabs) {
        Method getter = homepageGetter;
        if (getter == null) return null;
        try {
            Object owner = Modifier.isStatic(getter.getModifiers())
                    ? null : AdaptiveDexResolver.singletonOwner(getter);
            if (!Modifier.isStatic(getter.getModifiers()) && owner == null) return null;
            Class<?>[] params = getter.getParameterTypes();
            if (params.length == 0) return getter.invoke(owner);
            if (params.length == 1 && params[0] == boolean.class) {
                return getter.invoke(owner, Boolean.FALSE);
            }
            if (params.length == 2 && params[0] == boolean.class && params[1] == boolean.class) {
                return getter.invoke(owner, Boolean.FALSE, forZeroTabs);
            }
        } catch (Throwable t) {
            hooks.warn("adaptive homepage invocation failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private Object findTab(Object model, String wanted) {
        for (int i = 0, total = count(model); i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String current = gurlText(Reflect.call(tab, "getUrl"));
                if ((isNtp(wanted) && isNtp(current)) || wanted.equals(current)) return tab;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object openHomeTab(Object model, Object home) {
        for (OpenAttempt attempt : new OpenAttempt[]{
                new OpenAttempt("openTabProgrammatically", new Object[]{home, 2, Boolean.FALSE}),
                new OpenAttempt("createNewTab", new Object[]{home}),
                new OpenAttempt("createNewTabWithIndex", new Object[]{home, Boolean.FALSE, count(model)})}) {
            try {
                Object value = Reflect.call(model, attempt.method, attempt.args);
                if (value != null) {
                    hooks.info("adaptive home tab opened via " + attempt.method);
                    return value;
                }
            } catch (Throwable ignored) {}
        }

        // Final structural fallback: a non-static method returning Tab whose first parameter is GURL.
        try {
            Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
            Class<?> gurlType = Reflect.cls(loader, Chrome145.GURL);
            Method found = null;
            for (Method method : model.getClass().getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || !tabType.isAssignableFrom(method.getReturnType())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != gurlType) continue;
                if (found != null) return null;
                found = method;
            }
            if (found != null && found.getParameterCount() == 1) {
                found.setAccessible(true);
                return found.invoke(model, home);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int count(Object model) {
        if (model == null) return 0;
        try {
            Object value = Reflect.call(model, "getCount");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        try {
            Method method = Reflect.signature(model.getClass(), int.class);
            Object value = method == null ? null : method.invoke(model);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        return 0;
    }

    private Object tabAt(Object model, int index) {
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) { return null; }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String method : new String[]{"getSpec", "j", "n", "e", "g"}) {
            try {
                Object value = Reflect.call(gurl, method);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        try {
            Object value = Reflect.get(gurl, "a");
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isChromeTabbedActivity(Activity activity) {
        try { return Reflect.cls(loader, Chrome145.ACTIVITY).isInstance(activity); }
        catch (Throwable ignored) { return Chrome145.ACTIVITY.equals(activity.getClass().getName()); }
    }

    private static boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }

    private static final class OpenAttempt {
        final String method;
        final Object[] args;

        OpenAttempt(String method, Object[] args) {
            this.method = method;
            this.args = args;
        }
    }
}
