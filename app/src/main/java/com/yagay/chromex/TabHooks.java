package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

final class TabHooks {
    private static final int MAX_COLD_ROUNDS = 4;
    private static final long COLD_DELAY_MS = 1500L;
    private static final long RETRY_DELAY_MS = 1500L;

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Object>> models = new ArrayList<>();
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());

    TabHooks(XposedModule module, HookSupport hooks, SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        installNoRestoreSwitch();
        installModelCapture();
        installActivityLifecycle();
        installNewTabRedirect();
    }

    private void installNoRestoreSwitch() {
        // Current Chromium keeps this API stable. Prefer it over a release-specific R8 class.
        hooks.exact(loader, "org.chromium.base.CommandLine", "hasSwitch",
                new Class<?>[]{String.class}, "chromex:tabs:no-restore:stable", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });

        // Chrome 145 fallback.
        hooks.exact(loader, Chrome145.COMMAND_FLAGS, "c", new Class<?>[]{String.class},
                "chromex:tabs:no-restore:145", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
    }

    private void installModelCapture() {
        try {
            Class<?> type = Reflect.cls(loader, Chrome145.TAB_MODEL);
            Method target = null;
            try {
                target = Reflect.exact(type, "getCount");
            } catch (Throwable ignored) {}
            if (target == null) {
                ArrayList<Method> candidates = new ArrayList<>();
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                        m.setAccessible(true);
                        candidates.add(m);
                    }
                }
                if (candidates.size() == 1) target = candidates.get(0);
            }
            if (target == null) {
                hooks.warn("TabModel capture skipped: no unambiguous count method");
                return;
            }
            Method method = target;
            hooks.method(method, "chromex:tabs:model", chain -> {
                Object result = chain.proceed();
                remember(chain.getThisObject());
                return result;
            });
        } catch (Throwable t) {
            hooks.error("install model capture", t);
        }
    }

    private void installActivityLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) scheduleColdStart((Activity) receiver);
                    return result;
                });

        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex:tabs:onDestroy", chain -> {
                    Activity activity = chain.getThisObject() instanceof Activity
                            ? (Activity) chain.getThisObject() : null;
                    if (activity != null && activity.isFinishing()
                            && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                        try {
                            closeAllKnownTabs(activity);
                            clearClosedHistory();
                        } catch (Throwable t) {
                            hooks.error("exit cleanup", t);
                        }
                    }
                    return chain.proceed();
                });
    }

    private void installNewTabRedirect() {
        // Current Chromium path. ChromeTabCreator is a stable Java class and LoadUrlParams exposes
        // getUrl()/setUrl(), so this survives R8 changes to short class and field names.
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
                            String home = resolveHomeUrl();
                            if (!isNtp(home)) Reflect.call(params, "setUrl", home);
                        }
                    } catch (Throwable t) {
                        hooks.warn("stable createNewTab redirect skipped: " + t.getClass().getSimpleName());
                    }
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.CHROME_TAB_CREATOR, "launchUrl",
                "chromex:tabs:launchUrl", chain -> {
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

        // Chrome 145 R8 fallback.
        hooks.all(loader, Chrome145.TAB_CREATOR, "l", "chromex:tabs:creator:145", chain -> {
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

        // JNI bridge fallback. It is also useful on versions where tab creation bypasses
        // ChromeTabCreator for a particular launch source.
        hooks.all(loader, Chrome145.TAB_MODEL, "openNewTab", "chromex:tabs:openNewTab", chain -> {
            if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().size() < 2) {
                return chain.proceed();
            }
            Object gurl = chain.getArg(1);
            if (gurl == null) return chain.proceed();
            String current = gurlText(gurl);
            if (!isNtp(current)) return chain.proceed();
            String home = resolveHomeUrl();
            if (isNtp(home)) return chain.proceed();

            Object[] args = chain.getArgs().toArray();
            args[1] = Reflect.construct(Reflect.cls(loader, Chrome145.GURL), home);
            return chain.proceed(args);
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
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object regular = findRegularModel(activity);
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

            closeEverythingExcept(keep);
            if (count(regular) <= 1 || round + 1 >= MAX_COLD_ROUNDS) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS)) clearClosedHistory();
                hooks.info("cold start settled at round " + round);
            } else {
                retry(activity, round);
            }
        } catch (Throwable t) {
            hooks.error("cold round " + round, t);
            retry(activity, round);
        }
    }

    private void retry(Activity activity, int round) {
        if (round + 1 >= MAX_COLD_ROUNDS) return;
        main.postDelayed(() -> coldRound(activity, round + 1), RETRY_DELAY_MS);
    }

    private void remember(Object model) {
        if (model == null) return;
        synchronized (models) {
            Iterator<WeakReference<Object>> it = models.iterator();
            while (it.hasNext()) {
                Object value = it.next().get();
                if (value == null) it.remove();
                else if (value == model) return;
            }
            models.add(new WeakReference<>(model));
        }
    }

    private List<Object> knownModels() {
        ArrayList<Object> out = new ArrayList<>();
        synchronized (models) {
            Iterator<WeakReference<Object>> it = models.iterator();
            while (it.hasNext()) {
                Object value = it.next().get();
                if (value == null) it.remove();
                else out.add(value);
            }
        }
        return out;
    }

    private Object findRegularModel(Activity activity) {
        // Prefer the stable TabModelSelector type when it is directly retained by the activity.
        try {
            Class<?> selectorType = Reflect.cls(loader,
                    "org.chromium.chrome.browser.tabmodel.TabModelSelector");
            Object selector = Reflect.findFieldValueByType(activity, selectorType);
            if (selector != null) {
                for (String method : new String[]{"getModel", "getTabModel"}) {
                    try {
                        Object model = Reflect.call(selector, method, Boolean.FALSE);
                        if (model != null) {
                            remember(model);
                            return model;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // Chrome 145 selector fallback.
        try {
            Class<?> selectorType = Reflect.cls(loader, Chrome145.SELECTOR);
            Object selector = Reflect.findFieldValueByType(activity, selectorType);
            if (selector != null) {
                try {
                    Object model = Reflect.call(selector, "l", Boolean.FALSE);
                    if (model != null) {
                        remember(model);
                        return model;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        for (Object model : knownModels()) {
            if (!incognito(model)) return model;
        }
        return null;
    }

    private String resolveHomeUrl() {
        // Stable current Chromium API.
        try {
            Class<?> manager = Reflect.cls(loader, Chrome145.HOMEPAGE_MANAGER);
            Object instance = Reflect.callStatic(manager, "getInstance");
            if (instance != null) {
                Object gurl = Reflect.call(instance, "getHomepageGurl", Boolean.FALSE);
                String value = gurlText(gurl);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (Throwable ignored) {}

        // Chrome 145 R8 fallback.
        try {
            Class<?> manager = Reflect.cls(loader, Chrome145.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            if (instance == null) return Chrome145.NTP;
            Object gurl = Reflect.call(instance, "b", Boolean.FALSE);
            String value = gurlText(gurl);
            return value == null || value.isBlank() ? Chrome145.NTP : value;
        } catch (Throwable t) {
            return Chrome145.NTP;
        }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        try {
            Object value = Reflect.call(gurl, "getSpec");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        try {
            Object value = Reflect.call(gurl, "j");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        try {
            Object value = Reflect.get(gurl, "a");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isNtp(String url) {
        if (url == null) return false;
        return url.startsWith("chrome-native://newtab") || url.startsWith("chrome://newtab");
    }

    private Object findHomeTab(Object model, String home) {
        int total = count(model);
        for (int i = 0; i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String url = gurlText(Reflect.call(tab, "getUrl"));
                if (isNtp(url) || (home != null && home.equals(url))) return tab;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object openHomeTab(Object model, String url) {
        try {
            Class<?> gurlType = Reflect.cls(loader, Chrome145.GURL);
            Object gurl = Reflect.construct(gurlType, url);
            for (Method method : model.getClass().getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[1] != int.class) continue;
                if (!p[0].isAssignableFrom(gurlType) && !gurlType.isAssignableFrom(p[0])) continue;
                if (method.getReturnType() == void.class) continue;
                method.setAccessible(true);
                return method.invoke(model, gurl, 2);
            }
        } catch (Throwable t) {
            hooks.warn("open home tab fallback unavailable: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private int count(Object model) {
        try {
            try {
                Object value = Reflect.exact(model.getClass(), "getCount").invoke(model);
                if (value instanceof Integer) return (Integer) value;
            } catch (Throwable ignored) {}
            Method m = uniqueNoArg(model.getClass(), int.class);
            Object value = m == null ? null : m.invoke(model);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean incognito(Object model) {
        try {
            for (String name : new String[]{"isIncognito", "isOffTheRecord"}) {
                try {
                    Object value = Reflect.exact(model.getClass(), name).invoke(model);
                    if (value instanceof Boolean) return (Boolean) value;
                } catch (Throwable ignored) {}
            }
            Method m = uniqueNoArg(model.getClass(), boolean.class);
            Object value = m == null ? null : m.invoke(model);
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private Object tabAt(Object model, int index) {
        try {
            try {
                return Reflect.exact(model.getClass(), "getTabAt", int.class).invoke(model, index);
            } catch (Throwable ignored) {}
            Method candidate = null;
            for (Method m : model.getClass().getMethods()) {
                if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != int.class
                        || m.getReturnType().isPrimitive() || m.getReturnType() == void.class) continue;
                if (candidate != null) return null;
                candidate = m;
            }
            if (candidate != null) return candidate.invoke(model, index);
        } catch (Throwable ignored) {}
        return null;
    }

    private void closeEverythingExcept(Object keep) {
        for (Object model : knownModels()) {
            int total = count(model);
            for (int i = total - 1; i >= 0; i--) {
                Object tab = tabAt(model, i);
                if (tab != null && tab != keep) closeTab(model, tab);
            }
        }
    }

    private void closeAllKnownTabs(Activity activity) {
        // Current TabModel API provides closeAllTabs(boolean allowDelegation, boolean uponExit).
        for (Object model : knownModels()) {
            boolean closed = false;
            try {
                Reflect.call(model, "closeAllTabs", Boolean.FALSE, Boolean.TRUE);
                closed = true;
            } catch (Throwable ignored) {}
            if (!closed) {
                try {
                    Reflect.call(model, "closeAllTabs");
                    closed = true;
                } catch (Throwable ignored) {}
            }
            if (!closed) {
                for (int i = count(model) - 1; i >= 0; i--) {
                    Object tab = tabAt(model, i);
                    if (tab != null) closeTab(model, tab);
                }
            }
        }

        // Chrome 145 R8 fallback, useful when no model has been captured yet.
        try {
            Class<?> selectorType = Reflect.cls(loader, Chrome145.SELECTOR);
            Object selector = Reflect.findFieldValueByType(activity, selectorType);
            if (selector != null) {
                Object runnable = Reflect.construct(Reflect.cls(loader, Chrome145.CLOSE_ALL_RUNNABLE));
                Reflect.set(runnable, "O", selector);
                Reflect.setBoolean(runnable, "P", false);
                Reflect.setBoolean(runnable, "Q", false);
                if (runnable instanceof Runnable) ((Runnable) runnable).run();
            }
        } catch (Throwable ignored) {}
    }

    private void closeTab(Object model, Object tab) {
        // Current Chromium: closeTab(Tab, boolean animate, boolean uponExit, boolean canUndo).
        try {
            Reflect.call(model, "closeTab", tab, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
            return;
        } catch (Throwable ignored) {}

        try {
            try {
                Method exact = Reflect.exact(model.getClass(), "closeTab", tab.getClass());
                exact.invoke(model, tab);
                return;
            } catch (Throwable ignored) {}
            Method candidate = null;
            for (Method m : model.getClass().getMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != void.class) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(tab.getClass())) continue;
                if (candidate != null) return;
                candidate = m;
            }
            if (candidate != null) candidate.invoke(model, tab);
        } catch (Throwable ignored) {}
    }

    private Method uniqueNoArg(Class<?> type, Class<?> returnType) {
        Method found = null;
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                        || m.getReturnType() != returnType) continue;
                if (found != null) return null;
                m.setAccessible(true);
                found = m;
            }
            c = c.getSuperclass();
        }
        return found;
    }

    private void clearClosedHistory() {
        // Legacy native path. Kept isolated so failure here never breaks tab closing.
        try {
            Class<?> pm = Reflect.cls(loader, Chrome145.PROFILE_MANAGER);
            Object profile;
            try {
                profile = Reflect.callStatic(pm, "getLastUsedRegularProfile");
            } catch (Throwable ignored) {
                profile = Reflect.exact(pm, "b").invoke(null);
            }
            if (profile == null) return;
            Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
            Method method = Reflect.exact(nativeClass, "VIOOOOOOO",
                    int.class, int.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class);
            method.invoke(null, 0, 4, profile, null,
                    new int[]{8}, new String[0], new int[0], new String[0], new int[0]);
        } catch (Throwable t) {
            hooks.warn("clear closed-tab history legacy path unavailable: "
                    + t.getClass().getSimpleName());
        }
    }
}
