package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Exact Chrome 145.0.7632.218 tab/homepage profile. */
final class Chrome145TabsHooks {
    private static final long COLD_DELAY_MS = 500L;
    private static final long RETRY_DELAY_MS = 600L;
    private static final int MAX_ROUNDS = 6;

    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());

    Chrome145TabsHooks(HookSupport hooks, SharedPreferences prefs, ClassLoader loader) {
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        installNoRestore();
        installLifecycle();
        installNewTabRedirects();
    }

    private void installNoRestore() {
        hooks.exact(loader, Chrome145.COMMAND_FLAGS, "c", new Class<?>[]{String.class},
                "chromex145:tabs:no-restore", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                    return chain.proceed();
                });
    }

    private void installLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex145:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) scheduleColdStart((Activity) receiver);
                    return result;
                });

        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex145:tabs:onDestroy", chain -> {
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) {
                        Activity activity = (Activity) receiver;
                        if (activity.isFinishing() && isChromeTabbedActivity(activity)
                                && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                            boolean suppressed = closeAllModels(activity, true);
                            if (!suppressed) clearClosedHistory();
                            hooks.info("Chrome 145 exit cleanup applied; restoreSuppressed=" + suppressed);
                        }
                    }
                    return chain.proceed();
                });
    }

    private boolean isChromeTabbedActivity(Activity activity) {
        try { return Reflect.cls(loader, Chrome145.ACTIVITY).isInstance(activity); }
        catch (Throwable ignored) { return Chrome145.ACTIVITY.equals(activity.getClass().getName()); }
    }

    private void installNewTabRedirects() {
        hooks.all(loader, Chrome145.CHROME_TAB_CREATOR, "createNewTab",
                "chromex145:tabs:createNewTab", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object params = chain.getArg(0);
                    if (params == null) return chain.proceed();
                    try {
                        Object raw = Reflect.call(params, "getUrl");
                        if (raw instanceof String && isNtp((String) raw)) {
                            String home = resolveHomeUrl();
                            if (!isNtp(home)) Reflect.call(params, "setUrl", home);
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.CHROME_TAB_CREATOR, "launchUrl",
                "chromex145:tabs:launchUrl", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object first = chain.getArg(0);
                    if (!(first instanceof String) || !isNtp((String) first)) return chain.proceed();
                    String home = resolveHomeUrl();
                    if (isNtp(home)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = home;
                    return chain.proceed(args);
                });

        hooks.all(loader, Chrome145.TAB_CREATOR, "l", "chromex145:tabs:creator", chain -> {
            if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                return chain.proceed();
            }
            Object request = chain.getArg(0);
            if (request == null) return chain.proceed();
            try {
                Object raw = Reflect.get(request, "a");
                if (raw instanceof String && isNtp((String) raw)) {
                    String home = resolveHomeUrl();
                    if (!isNtp(home)) Reflect.set(request, "a", home);
                }
            } catch (Throwable ignored) {}
            return chain.proceed();
        });
    }

    private void scheduleColdStart(Activity activity) {
        if (!Config.get(prefs, Config.CLEAN_START)) return;
        Intent intent = activity.getIntent();
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getData() != null) return;
        synchronized (handled) {
            if (!handled.add(activity)) return;
        }
        main.postDelayed(() -> coldRound(activity, 0), COLD_DELAY_MS);
    }

    private void coldRound(Activity activity, int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object regular = model(activity, false);
            if (regular == null) {
                retry(activity, round);
                return;
            }
            String home = resolveHomeUrl();
            Object keep = findHomeTab(regular, home);
            if (keep == null) keep = openHomeTab(regular, home);
            if (keep == null) {
                retry(activity, round);
                return;
            }

            boolean suppressed = TabCloseStrategy.closeExcept(loader, regular, keep, hooks, false);
            Object incognito = model(activity, true);
            if (incognito != null && incognito != regular) {
                suppressed &= TabCloseStrategy.closeAll(loader, incognito, hooks, false);
            }

            if (count(regular) <= 1 || round + 1 >= MAX_ROUNDS) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS) && !suppressed) clearClosedHistory();
                hooks.info("Chrome 145 cold start settled on configured homepage at round " + round
                        + " restoreSuppressed=" + suppressed);
            } else {
                retry(activity, round);
            }
        } catch (Throwable t) {
            hooks.error("Chrome 145 cold-start round " + round, t);
            retry(activity, round);
        }
    }

    private void retry(Activity activity, int round) {
        if (round + 1 >= MAX_ROUNDS) return;
        main.postDelayed(() -> coldRound(activity, round + 1), RETRY_DELAY_MS);
    }

    private Object model(Activity activity, boolean incognito) {
        try {
            Class<?> type = Reflect.cls(loader, "org.chromium.chrome.browser.tabmodel.TabModelSelector");
            Object selector = Reflect.findFieldValueByType(activity, type);
            if (selector != null) {
                for (String method : new String[]{"getModel", "getTabModel"}) {
                    try {
                        Object value = Reflect.call(selector, method, incognito);
                        if (value != null) return value;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> type = Reflect.cls(loader, Chrome145.SELECTOR);
            Object selector = Reflect.findFieldValueByType(activity, type);
            if (selector != null) return Reflect.call(selector, "l", incognito);
        } catch (Throwable ignored) {}
        return null;
    }

    private String resolveHomeUrl() {
        try {
            Class<?> manager = Reflect.cls(loader, Chrome145.HOMEPAGE_MANAGER);
            Object instance = Reflect.callStatic(manager, "getInstance");
            if (instance != null) {
                Object gurl = Reflect.call(instance, "getHomepageGurl", Boolean.FALSE);
                String value = gurlText(gurl);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> manager = Reflect.cls(loader, Chrome145.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            if (instance == null) return Chrome145.NTP;
            Object gurl = Reflect.call(instance, "b", Boolean.FALSE);
            String value = gurlText(gurl);
            return value == null || value.isBlank() ? Chrome145.NTP : value;
        } catch (Throwable ignored) { return Chrome145.NTP; }
    }

    private Object findHomeTab(Object model, String home) {
        int total = count(model);
        for (int i = 0; i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String url = gurlText(Reflect.call(tab, "getUrl"));
                if ((isNtp(home) && isNtp(url)) || (home != null && home.equals(url))) return tab;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object openHomeTab(Object model, String url) {
        if (url == null || url.isBlank()) url = Chrome145.NTP;
        try {
            Class<?> gurlType = Reflect.cls(loader, Chrome145.GURL);
            Object gurl = Reflect.construct(gurlType, url);
            for (Method method : model.getClass().getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[1] != int.class || method.getReturnType() == void.class) continue;
                if (!p[0].isAssignableFrom(gurlType) && !gurlType.isAssignableFrom(p[0])) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                Object value = method.invoke(model, gurl, 2);
                if (value != null) return value;
            }
        } catch (Throwable t) {
            hooks.warn("Chrome 145 open-home unavailable: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private boolean closeAllModels(Activity activity, boolean uponExit) {
        boolean suppressed = true;
        suppressed &= TabCloseStrategy.closeAll(loader, model(activity, false), hooks, uponExit);
        suppressed &= TabCloseStrategy.closeAll(loader, model(activity, true), hooks, uponExit);
        return suppressed;
    }

    private int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        try {
            Method method = Reflect.signature(model.getClass(), int.class);
            Object value = method == null ? null : method.invoke(model);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private Object tabAt(Object model, int index) {
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) { return null; }
    }

    private void clearClosedHistory() {
        try {
            Class<?> pm = Reflect.cls(loader, Chrome145.PROFILE_MANAGER);
            Object profile;
            try { profile = Reflect.callStatic(pm, "getLastUsedRegularProfile"); }
            catch (Throwable ignored) { profile = Reflect.exact(pm, "b").invoke(null); }
            if (profile == null) return;
            Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
            Method method = Reflect.exact(nativeClass, "VIOOOOOOO",
                    int.class, int.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class);
            method.invoke(null, 0, 4, profile, null,
                    new int[]{8}, new String[0], new int[0], new String[0], new int[0]);
        } catch (Throwable t) {
            hooks.warn("Chrome 145 clear closed history unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String method : new String[]{"getSpec", "j", "n"}) {
            try {
                Object value = Reflect.call(gurl, method);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        try {
            Object value = Reflect.get(gurl, "a");
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean isNtp(String url) {
        return url != null && (url.startsWith("chrome-native://newtab")
                || url.startsWith("chrome://newtab"));
    }
}
