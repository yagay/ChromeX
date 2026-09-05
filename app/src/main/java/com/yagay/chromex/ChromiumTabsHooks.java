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

/** Shared tab/homepage feature for verified Chromium profiles. */
final class ChromiumTabsHooks {
    private final ChromiumProfile profile;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());
    private final ChromiumHistoryFallback history;

    ChromiumTabsHooks(ChromiumProfile profile, ClassLoader loader,
                      HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.loader = loader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.history = new ChromiumHistoryFallback(profile, loader, hooks);
    }

    void install() {
        installNoRestore();
        installLifecycle();
        installNewTabRedirects();
    }

    private void installNoRestore() {
        String owner = profile.is145() ? Chrome145.COMMAND_FLAGS : "org.chromium.base.CommandLine";
        hooks.exact(loader, owner, "c", new Class<?>[]{String.class},
                "chromex:tabs:no-restore:" + profile.family, chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                    return chain.proceed();
                });
    }

    private void installLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:tabs:onStart:" + profile.family, chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) scheduleColdStart((Activity) receiver);
                    return result;
                });

        if (profile.is152()) {
            hooks.exact(loader, Chrome145.ACTIVITY, "moveTaskToBack",
                    new Class<?>[]{boolean.class}, "chromex:tabs:exit-back", chain -> {
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof Activity
                                && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                            cleanupForExit((Activity) receiver, "moveTaskToBack");
                        }
                        return chain.proceed();
                    });
        }

        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex:tabs:onDestroy:" + profile.family, chain -> {
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

    private void cleanupForExit(Activity activity, String reason) {
        try {
            Object regular = model(activity, false);
            Object incognito = model(activity, true);
            boolean restoreSuppressed = true;
            restoreSuppressed &= TabCloseStrategy.closeAll(loader, regular, hooks, true);
            if (incognito != null && incognito != regular) {
                restoreSuppressed &= TabCloseStrategy.closeAll(loader, incognito, hooks, true);
            }
            if (!restoreSuppressed) history.clear(activity, "exit-fallback-" + reason);
            hooks.info(profile.label() + " exit tab cleanup via " + reason
                    + " restoreSuppressed=" + restoreSuppressed);
        } catch (Throwable t) {
            hooks.error(profile.label() + " exit cleanup via " + reason, t);
        }
    }

    private void installNewTabRedirects() {
        installStableCreatorRedirects();

        if (profile.is152()) {
            for (String method : new String[]{"a", "f", "j", "l", "m"}) {
                hooks.all(loader, Chrome152.TAB_CREATOR, method,
                        "chromex:tabs:creator152:" + method, chain -> {
                            if (Config.get(prefs, Config.NEWTAB_HOME)
                                    && !chain.getArgs().isEmpty()) {
                                redirectLoadUrlParam(chain.getArg(0), Chrome152.LOAD_URL_FIELD);
                            }
                            return chain.proceed();
                        });
            }
        } else {
            hooks.all(loader, Chrome145.TAB_CREATOR, "l", "chromex:tabs:creator145", chain -> {
                if (Config.get(prefs, Config.NEWTAB_HOME) && !chain.getArgs().isEmpty()) {
                    redirectLoadUrlParam(chain.getArg(0), "a");
                }
                return chain.proceed();
            });
        }

        hooks.all(loader, Chrome145.TAB_MODEL, "openTabProgrammatically",
                "chromex:tabs:newtab-model", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object current = chain.getArg(0);
                    if (!isNtp(gurlText(current))) return chain.proceed();
                    Object home = homepageGurl(false);
                    String spec = gurlText(home);
                    if (home == null || spec == null || spec.isBlank() || isNtp(spec)) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray();
                    args[0] = home;
                    hooks.info(profile.label() + " model-side new-tab redirected to homepage");
                    return chain.proceed(args);
                });
    }

    private void installStableCreatorRedirects() {
        hooks.all(loader, Chrome145.CHROME_TAB_CREATOR, "createNewTab",
                "chromex:tabs:createNewTab", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object params = chain.getArg(0);
                    if (params == null) return chain.proceed();
                    try {
                        Object raw = Reflect.call(params, "getUrl");
                        if (raw instanceof String && isNtp((String) raw)) {
                            String home = homepageText(false);
                            if (home != null && !isNtp(home)) Reflect.call(params, "setUrl", home);
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.CHROME_TAB_CREATOR, "launchUrl",
                "chromex:tabs:launchUrl", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object first = chain.getArg(0);
                    if (!(first instanceof String) || !isNtp((String) first)) return chain.proceed();
                    String home = homepageText(false);
                    if (home == null || isNtp(home)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = home;
                    return chain.proceed(args);
                });
    }

    private void redirectLoadUrlParam(Object params, String field) {
        if (params == null) return;
        try {
            Object raw = Reflect.get(params, field);
            if (!(raw instanceof String) || !isNtp((String) raw)) return;
            String home = homepageText(false);
            if (home == null || home.isBlank() || isNtp(home)) return;
            Reflect.set(params, field, home);
            hooks.info(profile.label() + " new-tab redirected to configured homepage");
        } catch (Throwable ignored) {}
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
            if (keep == null) keep = openHomeTab(regular, home, wanted);
            if (keep == null) {
                retry(activity, round);
                return;
            }

            boolean restoreSuppressed = TabCloseStrategy.closeExcept(
                    loader, regular, keep, hooks, false);
            Object incognito = model(activity, true);
            if (incognito != null && incognito != regular) {
                restoreSuppressed &= TabCloseStrategy.closeAll(loader, incognito, hooks, false);
            }

            if (count(regular) <= 1 || round + 1 >= profile.maxRounds) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS) && !restoreSuppressed) {
                    history.clear(activity, "cold-start-fallback");
                }
                hooks.info(profile.label() + " cold start settled at round " + round
                        + " restoreSuppressed=" + restoreSuppressed);
            } else {
                retry(activity, round);
            }
        } catch (Throwable t) {
            hooks.error(profile.label() + " cold-start round " + round, t);
            retry(activity, round);
        }
    }

    private void retry(Activity activity, int round) {
        if (round + 1 >= profile.maxRounds) return;
        main.postDelayed(() -> coldRound(activity, round + 1), profile.retryDelayMs);
    }

    private Object model(Activity activity, boolean incognito) {
        try {
            Class<?> stable = Reflect.cls(loader,
                    "org.chromium.chrome.browser.tabmodel.TabModelSelector");
            Object selector = Reflect.findFieldValueByType(activity, stable);
            if (selector != null) {
                for (String method : new String[]{"getModel", "getTabModel"}) {
                    try {
                        Object value = Reflect.call(selector, method, incognito);
                        if (value != null) return value;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (profile.is152()) {
            try {
                Object selector = Reflect.get(activity, Chrome152.ACTIVITY_SELECTOR_FIELD);
                if (selector == null) {
                    selector = Reflect.findFieldValueByType(activity,
                            Reflect.cls(loader, Chrome152.TAB_SELECTOR));
                }
                return selector == null ? null : Reflect.call(selector, "k", incognito);
            } catch (Throwable ignored) {
                return null;
            }
        }

        try {
            Object selector = Reflect.findFieldValueByType(activity,
                    Reflect.cls(loader, Chrome145.SELECTOR));
            return selector == null ? null : Reflect.call(selector, "l", incognito);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object homepageGurl(boolean forZeroTabs) {
        try {
            Class<?> stable = Reflect.cls(loader, Chrome145.HOMEPAGE_MANAGER);
            Object instance = Reflect.callStatic(stable, "getInstance");
            if (instance != null) {
                try {
                    Object value = Reflect.call(instance, "getHomepageGurl", Boolean.FALSE);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            if (profile.is152()) {
                Class<?> owner = Reflect.cls(loader, Chrome152.HOMEPAGE);
                Object instance = Reflect.callStatic(owner, "d");
                return instance == null ? null
                        : Reflect.call(instance, "b", Boolean.FALSE, forZeroTabs);
            }
            Class<?> owner = Reflect.cls(loader, Chrome145.HOMEPAGE);
            Object instance = Reflect.callStatic(owner, "d");
            return instance == null ? null : Reflect.call(instance, "b", Boolean.FALSE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String homepageText(boolean forZeroTabs) {
        String text = gurlText(homepageGurl(forZeroTabs));
        return text == null || text.isBlank() ? Chrome145.NTP : text;
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

    private Object openHomeTab(Object model, Object home, String wanted) {
        try {
            Object value = Reflect.call(model, "openTabProgrammatically", home, 2, Boolean.FALSE);
            if (value != null) return value;
        } catch (Throwable ignored) {}

        if (profile.is145()) {
            try {
                Class<?> gurlType = Reflect.cls(loader, Chrome145.GURL);
                Object gurl = home != null ? home : Reflect.construct(gurlType, wanted);
                for (Method method : model.getClass().getMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (p.length != 2 || p[1] != int.class || method.getReturnType() == void.class) {
                        continue;
                    }
                    if (!p[0].isAssignableFrom(gurlType) && !gurlType.isAssignableFrom(p[0])) continue;
                    try { method.setAccessible(true); } catch (Throwable ignored) {}
                    Object value = method.invoke(model, gurl, 2);
                    if (value != null) return value;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int count(Object model) {
        if (model == null) return 0;
        try {
            Object value = Reflect.call(model, "getCount");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        if (profile.is145()) {
            try {
                Method method = Reflect.signature(model.getClass(), int.class);
                Object value = method == null ? null : method.invoke(model);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable ignored) {}
        }
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
}
