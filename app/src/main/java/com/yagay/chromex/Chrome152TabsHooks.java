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

/** Single verified tab/homepage implementation for Chrome 152.0.7977.75. */
final class Chrome152TabsHooks {
    private static final long COLD_DELAY_MS = 1500L;
    private static final long RETRY_DELAY_MS = 1000L;
    private static final int MAX_ROUNDS = 4;

    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());
    private final Chrome152HistoryCleaner history;

    Chrome152TabsHooks(ClassLoader loader, HookSupport hooks, SharedPreferences prefs) {
        this.loader = loader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.history = new Chrome152HistoryCleaner(loader, hooks);
    }

    void install() {
        installNoRestore();
        installActivityLifecycle();
        installNewTabRedirects();
    }

    private void installNoRestore() {
        hooks.exact(loader, "org.chromium.base.CommandLine", "c",
                new Class<?>[]{String.class}, "chromex152:tabs:no-restore", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                    return chain.proceed();
                });
    }

    private void installActivityLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex152:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) scheduleColdStart((Activity) receiver);
                    return result;
                });

        // Chrome 152 declares this method directly. It is a much better user-exit signal than the
        // inherited ud4#onDestroy path and runs before task teardown.
        hooks.exact(loader, Chrome145.ACTIVITY, "moveTaskToBack",
                new Class<?>[]{boolean.class}, "chromex152:tabs:exit-back", chain -> {
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                        cleanupForExit((Activity) receiver, "moveTaskToBack");
                    }
                    return chain.proceed();
                });

        // Lifecycle fallback. Reflect.exact reaches inherited ud4#onDestroy, so guard the receiver
        // to ChromeTabbedActivity and perform cleanup before chain.proceed() while native state lives.
        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex152:tabs:exit-destroy", chain -> {
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

    private boolean isChromeTabbedActivity(Activity activity) {
        try {
            return Reflect.cls(loader, Chrome145.ACTIVITY).isInstance(activity);
        } catch (Throwable ignored) {
            return Chrome145.ACTIVITY.equals(activity.getClass().getName());
        }
    }

    private void cleanupForExit(Activity activity, String reason) {
        try {
            Object selector = selector(activity);
            if (selector != null) {
                closeAll(selectModel(selector, false));
                closeAll(selectModel(selector, true));
            }
            history.clear(activity, "exit-" + reason);
            hooks.info("Chrome 152 exit tab cleanup applied via " + reason);
        } catch (Throwable t) {
            hooks.error("Chrome 152 exit cleanup via " + reason, t);
        }
    }

    private void installNewTabRedirects() {
        for (String method : new String[]{"a", "f", "j", "l", "m"}) {
            hooks.all(loader, Chrome152.TAB_CREATOR, method,
                    "chromex152:tabs:creator:" + method, chain -> {
                        if (Config.get(prefs, Config.NEWTAB_HOME) && !chain.getArgs().isEmpty()) {
                            redirectLoadUrlParam(chain.getArg(0));
                        }
                        return chain.proceed();
                    });
        }

        hooks.all(loader, Chrome145.TAB_MODEL, "openTabProgrammatically",
                "chromex152:tabs:newtab-model", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object current = chain.getArg(0);
                    if (!isNtp(gurlText(current))) return chain.proceed();
                    Object home = homepageGurl(false, false);
                    String spec = gurlText(home);
                    if (home == null || spec == null || isNtp(spec)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = home;
                    hooks.info("Chrome 152 model-side new-tab redirected to configured homepage");
                    return chain.proceed(args);
                });
    }

    private void redirectLoadUrlParam(Object params) {
        if (params == null || !Chrome145.LOAD_URL_PARAMS.equals(params.getClass().getName())) return;
        try {
            Object raw = Reflect.get(params, Chrome152.LOAD_URL_FIELD);
            if (!(raw instanceof String) || !isNtp((String) raw)) return;
            Object home = homepageGurl(false, false);
            String spec = gurlText(home);
            if (spec == null || spec.isBlank() || isNtp(spec)) return;
            Reflect.set(params, Chrome152.LOAD_URL_FIELD, spec);
            hooks.info("Chrome 152 new-tab redirected to configured homepage");
        } catch (Throwable t) {
            hooks.warn("Chrome 152 new-tab redirect unavailable: " + t.getClass().getSimpleName());
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
        main.postDelayed(() -> coldRound(activity, 0), COLD_DELAY_MS);
    }

    private void coldRound(Activity activity, int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object selector = selector(activity);
            Object regular = selector == null ? null : selectModel(selector, false);
            if (regular == null) {
                retry(activity, round);
                return;
            }
            Object home = homepageGurl(false, true);
            String wanted = gurlText(home);
            if (home == null || wanted == null || wanted.isBlank()) {
                retry(activity, round);
                return;
            }

            Object keep = findTab(regular, wanted);
            if (keep == null) keep = Reflect.call(regular,
                    "openTabProgrammatically", home, 2, Boolean.FALSE);
            if (keep == null) {
                retry(activity, round);
                return;
            }

            closeEverythingExcept(regular, keep);
            Object incognito = selectModel(selector, true);
            if (incognito != null && incognito != regular) closeAll(incognito);

            if (count(regular) <= 1 || round + 1 >= MAX_ROUNDS) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                    history.clear(activity, "cold-start");
                }
                hooks.info("Chrome 152 cold start settled on configured homepage at round " + round);
            } else {
                retry(activity, round);
            }
        } catch (Throwable t) {
            hooks.error("Chrome 152 cold-start round " + round, t);
            retry(activity, round);
        }
    }

    private void retry(Activity activity, int round) {
        if (round + 1 >= MAX_ROUNDS) return;
        main.postDelayed(() -> coldRound(activity, round + 1), RETRY_DELAY_MS);
    }

    private Object homepageGurl(boolean incognito, boolean forZeroTabs) {
        try {
            Class<?> manager = Reflect.cls(loader, Chrome152.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            return instance == null ? null : Reflect.call(instance, "b", incognito, forZeroTabs);
        } catch (Throwable t) {
            hooks.warn("Chrome 152 homepage resolver unavailable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private Object selector(Activity activity) {
        try {
            Object value = Reflect.get(activity, Chrome152.ACTIVITY_SELECTOR_FIELD);
            if (value != null) return value;
        } catch (Throwable ignored) {}
        try {
            return Reflect.findFieldValueByType(activity,
                    Reflect.cls(loader, Chrome152.TAB_SELECTOR));
        } catch (Throwable ignored) { return null; }
    }

    private Object selectModel(Object selector, boolean incognito) {
        if (selector == null) return null;
        try { return Reflect.call(selector, "k", incognito); }
        catch (Throwable ignored) { return null; }
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

    private void closeEverythingExcept(Object model, Object keep) {
        for (int i = count(model) - 1; i >= 0; i--) {
            Object tab = tabAt(model, i);
            if (tab != null && tab != keep) closeTab(model, tab);
        }
    }

    private void closeAll(Object model) {
        if (model == null) return;
        try {
            Reflect.call(model, "forceCloseAllTabs");
            return;
        } catch (Throwable ignored) {}
        for (int i = count(model) - 1; i >= 0; i--) {
            Object tab = tabAt(model, i);
            if (tab != null) closeTab(model, tab);
        }
    }

    private void closeTab(Object model, Object tab) {
        try {
            Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
            Method method = Reflect.exact(model.getClass(), "closeTab", tabType);
            method.invoke(model, tab);
        } catch (Throwable ignored) {}
    }

    private int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private Object tabAt(Object model, int index) {
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) { return null; }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        try {
            Object value = Reflect.get(gurl, "a");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        for (String method : new String[]{"getSpec", "j", "n", "e", "g"}) {
            try {
                Object value = Reflect.call(gurl, method);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }
}
