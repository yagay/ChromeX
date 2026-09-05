package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Source-first tab/homepage engine with creator and timing fallbacks. */
final class UniversalTabsHooks {
    private static final String COMMAND_LINE = "org.chromium.base.CommandLine";
    private static final String TAB_SELECTOR = "org.chromium.chrome.browser.tabmodel.TabModelSelector";
    private static final String TAB = "org.chromium.chrome.browser.tab.Tab";
    private static final String LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";

    private final ChromiumProfile profile;
    private final BrowserCapabilities capabilities;
    private final ChromeRuntime runtime;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ResolvedBindings bindings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Activity> coldLaunched = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<String> hookedCreators = ConcurrentHashMap.newKeySet();
    private final ChromiumHistoryFallback history;
    private final RecentlyClosedBinding recentlyClosed;
    private final Method adaptiveHomepageGetter;

    UniversalTabsHooks(ChromiumProfile profile, BrowserCapabilities capabilities,
                       ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs,
                       ResolvedBindings bindings) {
        this.profile = profile;
        this.capabilities = capabilities;
        this.runtime = runtime;
        this.loader = runtime.classLoader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.bindings = bindings;
        this.history = new ChromiumHistoryFallback(profile, loader, hooks);
        this.recentlyClosed = new RecentlyClosedBinding(
                runtime, hooks, bindings == null ? null : bindings.recentlyClosedClear);
        this.adaptiveHomepageGetter = bindings == null ? null : bindings.homepageGetter;
    }

    void install() {
        installHomepageValueFallback();
        installNoRestore();
        installLifecycle();
        installTabStateReady();
        installNewTabSource();
        installStableNewTabRedirects();
        installResolvedCreator();
        installDeclaredLoadUrlCreators();
    }

    private void installHomepageValueFallback() {
        Method getter = adaptiveHomepageGetter;
        if (getter == null) return;
        hooks.method(getter, "chromex:universal:homepage-value", chain -> {
            Object direct = chain.proceed();
            if (!Config.get(prefs, Config.CLEAN_START)
                    && !Config.get(prefs, Config.NEWTAB_HOME)) return direct;
            Object resolved = AdaptiveHomepageFallback.fallbackAfterDirect(
                    direct, getter, runtime, hooks);
            if (resolved == direct || !AdaptiveHomepageFallback.usableGurl(resolved)) return direct;
            hooks.info("universal homepage binding replaced empty result from Chromium preferences");
            return resolved;
        });
    }

    private void installNoRestore() {
        Method structural = uniqueBooleanStringMethod(COMMAND_LINE);
        if (structural != null) {
            hooks.method(structural, "chromex:universal:tabs:no-restore", chain -> {
                if (Config.get(prefs, Config.CLEAN_START)
                        && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                return chain.proceed();
            });
            return;
        }
        if (!profile.isVerifiedExact()) {
            hooks.warn("universal no-restore unavailable; restore-ready cleanup will be used");
            return;
        }
        String owner = profile.is145() ? Chrome145.COMMAND_FLAGS : COMMAND_LINE;
        try {
            hooks.exact(loader, owner, "c", new Class<?>[]{String.class},
                    "chromex:universal:tabs:no-restore-exact", chain -> {
                        if (Config.get(prefs, Config.CLEAN_START)
                                && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                        return chain.proceed();
                    });
        } catch (Throwable ignored) {}
    }

    private void installLifecycle() {
        if (!hasClass(Chrome145.ACTIVITY)) return;
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:universal:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity && isChromeTabbedActivity((Activity) receiver)) {
                        Activity activity = (Activity) receiver;
                        installLiveCreators(activity);
                        registerColdStart(activity);
                    }
                    return result;
                });

        if (hasMethod(Chrome145.ACTIVITY, "moveTaskToBack")) {
            hooks.exact(loader, Chrome145.ACTIVITY, "moveTaskToBack", new Class<?>[]{boolean.class},
                    "chromex:universal:tabs:exit-back", chain -> {
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof Activity && isChromeTabbedActivity((Activity) receiver)
                                && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                            cleanupForExit((Activity) receiver, "moveTaskToBack");
                        }
                        return chain.proceed();
                    });
        }

        try {
            hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                    "chromex:universal:tabs:onDestroy", chain -> {
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
        } catch (Throwable ignored) {}
    }

    /** Prefer Chromium's real restore-complete signal over guessing startup delays. */
    private void installTabStateReady() {
        Method ready = bindings == null ? null : bindings.tabStateReady;
        if (ready == null) return;
        hooks.method(ready, "chromex:universal:tabs:state-ready", chain -> {
            Object result = chain.proceed();
            main.post(this::launchPendingColdStarts);
            return result;
        });
        hooks.info("tab restore-ready source bound: "
                + ready.getDeclaringClass().getName() + '#' + ready.getName());
    }

    /**
     * Modern Chromium funnels NTP launches through TabCreatorUtil.launchNtp. Redirect there before
     * the NTP URL is materialized; creator argument rewriting remains the fallback for old forks.
     */
    private void installNewTabSource() {
        Method source = bindings == null ? null : bindings.newTabSource;
        if (source == null) return;
        hooks.method(source, "chromex:universal:tabs:newtab-source", chain -> {
            if (!Config.get(prefs, Config.NEWTAB_HOME)) return chain.proceed();
            Object home = homepageGurl(false);
            String wanted = ChromiumUrlAccessor.text(home);
            if (wanted == null || wanted.isBlank() || ChromiumUrlAccessor.isNtp(wanted)) {
                return chain.proceed();
            }
            Object creator = chain.getArg(0);
            Object type = chain.getArg(2);
            if (creator == null || !(type instanceof Number)) return chain.proceed();
            try {
                Object opened = Reflect.call(creator, "launchUrl", wanted,
                        ((Number) type).intValue());
                hooks.info("new-tab redirected at Chromium source -> " + wanted);
                return opened;
            } catch (Throwable t) {
                hooks.warn("new-tab source redirect unavailable: " + t.getClass().getSimpleName());
                return chain.proceed();
            }
        });
    }

    private void installStableNewTabRedirects() {
        for (String method : new String[]{
                "openTabProgrammatically", "createNewTab", "createNewTabWithIndex", "openNewTab"}) {
            if (!hasMethod(Chrome145.TAB_MODEL, method)) continue;
            hooks.all(loader, Chrome145.TAB_MODEL, method,
                    "chromex:universal:tabs:model:" + method, chain -> {
                        if (!Config.get(prefs, Config.NEWTAB_HOME)) return chain.proceed();
                        Object home = homepageGurl(false);
                        String wanted = ChromiumUrlAccessor.text(home);
                        if (home == null || wanted == null || ChromiumUrlAccessor.isNtp(wanted)) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        if (!rewriteCreatorArgs(args, home, wanted)) return chain.proceed();
                        hooks.info("new-tab redirected through TabModel fallback -> " + wanted);
                        return chain.proceed(args);
                    });
        }
    }

    private void installResolvedCreator() {
        Method creator = bindings == null ? null : bindings.tabCreator;
        if (creator != null) hookCreatorMethods(creator.getDeclaringClass());
    }

    private void installDeclaredLoadUrlCreators() {
        try { hookCreatorMethods(Reflect.cls(loader, Chrome145.CHROME_TAB_CREATOR)); }
        catch (Throwable ignored) {}
    }

    private void installLiveCreators(Activity activity) {
        if (activity == null) return;
        forEachActivityField(activity, candidate -> hookCreatorMethods(candidate.getClass()));
    }

    private void hookCreatorMethods(Class<?> start) {
        Class<?> type = start;
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isLoadUrlCreator(method)) continue;
                String key = method.toGenericString();
                if (!hookedCreators.add(key)) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                hooks.method(method, "chromex:universal:tabs:creator:" + hookedCreators.size(),
                        chain -> {
                            if (!Config.get(prefs, Config.NEWTAB_HOME)) return chain.proceed();
                            Object home = homepageGurl(false);
                            String wanted = ChromiumUrlAccessor.text(home);
                            if (home == null || wanted == null || ChromiumUrlAccessor.isNtp(wanted)) {
                                return chain.proceed();
                            }
                            Object[] args = chain.getArgs().toArray();
                            if (!rewriteCreatorArgs(args, home, wanted)) return chain.proceed();
                            hooks.info("new-tab redirected through structural creator "
                                    + method.getDeclaringClass().getName() + '#' + method.getName()
                                    + " -> " + wanted);
                            return chain.proceed(args);
                        });
            }
            type = type.getSuperclass();
        }
    }

    private boolean rewriteCreatorArgs(Object[] args, Object home, String wanted) {
        if (args == null) return false;
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            if (Chrome145.GURL.equals(arg.getClass().getName()) && ChromiumUrlAccessor.isNtp(arg)) {
                args[i] = home;
                changed = true;
            } else if (arg instanceof String && ChromiumUrlAccessor.isNtp((String) arg)) {
                args[i] = wanted;
                changed = true;
            } else if (LOAD_URL_PARAMS.equals(arg.getClass().getName())) {
                changed |= rewriteLoadParams(arg, home, wanted);
            }
        }
        return changed;
    }

    private boolean rewriteLoadParams(Object params, Object home, String wanted) {
        if (params == null) return false;
        boolean changed = false;
        Class<?> type = params.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(params);
                    if (value instanceof String && ChromiumUrlAccessor.isNtp((String) value)) {
                        field.set(params, wanted);
                        changed = true;
                    } else if (value != null && Chrome145.GURL.equals(value.getClass().getName())
                            && ChromiumUrlAccessor.isNtp(value)) {
                        field.set(params, home);
                        changed = true;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return changed;
    }

    private void registerColdStart(Activity activity) {
        if (!Config.get(prefs, Config.CLEAN_START)) return;
        Intent intent = activity.getIntent();
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getData() != null) {
            return;
        }
        synchronized (handled) {
            if (!handled.add(activity)) return;
        }
        if (isTabStateInitialized(activity)) {
            main.post(() -> launchColdStart(activity, "already-ready"));
        } else {
            long fallback = Math.max(1400L, profile.coldDelayMs * 3L);
            main.postDelayed(() -> launchColdStart(activity, "timing-fallback"), fallback);
        }
    }

    private void launchPendingColdStarts() {
        ArrayList<Activity> activities;
        synchronized (handled) { activities = new ArrayList<>(handled); }
        for (Activity activity : activities) launchColdStart(activity, "tab-state-ready");
    }

    private void launchColdStart(Activity activity, String source) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (coldLaunched) {
            if (!coldLaunched.add(activity)) return;
        }
        hooks.info("cold-start cleanup triggered by " + source);
        coldRound(activity, 0);
    }

    private void coldRound(Activity activity, int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            installLiveCreators(activity);
            Object regular = model(activity, false);
            Object home = homepageGurl(true);
            String wanted = ChromiumUrlAccessor.text(home);
            if (regular == null || home == null || wanted == null || wanted.isBlank()) {
                retry(activity, round, "model/home unavailable");
                return;
            }

            Object keep = findTab(regular, wanted);
            if (keep == null) keep = openHomeTab(activity, regular, home);
            if (keep == null) {
                retry(activity, round, "creator unavailable");
                return;
            }

            boolean restoreSuppressed = TabCloseStrategy.closeExcept(
                    loader, regular, keep, hooks, false);
            Object incognito = model(activity, true);
            if (incognito != null && incognito != regular) {
                restoreSuppressed &= TabCloseStrategy.closeAll(loader, incognito, hooks, false);
            }

            int count = count(regular);
            if (count <= 1 || round + 1 >= profile.maxRounds) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS) && !restoreSuppressed) {
                    if (!recentlyClosed.clear(activity, "cold-start") && profile.isVerifiedExact()) {
                        history.clear(activity, "cold-start-fallback");
                    }
                }
                hooks.info("universal cold start settled at round " + round
                        + " tabs=" + count + " homepage=" + wanted
                        + " restoreSuppressed=" + restoreSuppressed);
            } else {
                retry(activity, round, "tabs=" + count);
            }
        } catch (Throwable t) {
            hooks.warn("universal cold-start round " + round + " failed: "
                    + t.getClass().getSimpleName());
            retry(activity, round, t.getClass().getSimpleName());
        }
    }

    private void retry(Activity activity, int round, String reason) {
        if (round + 1 >= profile.maxRounds) {
            hooks.warn("universal cold start unresolved after " + profile.maxRounds
                    + " rounds: " + reason);
            return;
        }
        main.postDelayed(() -> coldRound(activity, round + 1), profile.retryDelayMs);
    }

    private void cleanupForExit(Activity activity, String reason) {
        try {
            Object regular = model(activity, false);
            Object incognito = model(activity, true);
            boolean restoreSuppressed = TabCloseStrategy.closeAll(loader, regular, hooks, true);
            if (incognito != null && incognito != regular) {
                restoreSuppressed &= TabCloseStrategy.closeAll(loader, incognito, hooks, true);
            }
            if (!restoreSuppressed) {
                if (!recentlyClosed.clear(activity, reason) && profile.isVerifiedExact()) {
                    history.clear(activity, reason);
                }
            }
            hooks.info("universal exit tab cleanup via " + reason
                    + " restoreSuppressed=" + restoreSuppressed);
        } catch (Throwable t) {
            hooks.error("universal exit cleanup via " + reason, t);
        }
    }

    private Object homepageGurl(boolean forZeroTabs) {
        try {
            Class<?> stable = Reflect.cls(loader, Chrome145.HOMEPAGE_MANAGER);
            Object instance = Reflect.callStatic(stable, "getInstance");
            if (instance != null) {
                for (Object[] args : new Object[][]{{Boolean.FALSE}, {Boolean.FALSE, forZeroTabs}, {}}) {
                    for (String name : new String[]{"getHomepageGurl", "getHomepageGURL", "getHomepageUrl"}) {
                        try {
                            Object value = Reflect.call(instance, name, args);
                            if (value != null && ChromiumUrlAccessor.text(value) != null) return value;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (profile.is152()) {
            try {
                Class<?> owner = Reflect.cls(loader, Chrome152.HOMEPAGE);
                Object instance = Reflect.callStatic(owner, "d");
                Object value = instance == null ? null : Reflect.call(instance, "b", false, forZeroTabs);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        } else if (profile.is145()) {
            try {
                Class<?> owner = Reflect.cls(loader, Chrome145.HOMEPAGE);
                Object instance = Reflect.callStatic(owner, "d");
                Object value = instance == null ? null : Reflect.call(instance, "b", false);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }

        if (adaptiveHomepageGetter != null) {
            Object value = AdaptiveHomepageFallback.resolve(
                    adaptiveHomepageGetter, runtime, hooks, forZeroTabs);
            if (value != null) return value;
        }
        return null;
    }

    private Object findTab(Object model, String wanted) {
        int total = count(model);
        for (int i = 0; i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String current = ChromiumUrlAccessor.text(Reflect.call(tab, "getUrl"));
                if (wanted.equals(current)
                        || (ChromiumUrlAccessor.isNtp(wanted) && ChromiumUrlAccessor.isNtp(current))) {
                    return tab;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object openHomeTab(Activity activity, Object model, Object home) {
        Object opened = invokeModelCreator(model, home);
        if (opened != null) return opened;
        final Object[] result = new Object[1];
        forEachActivityField(activity, candidate -> {
            if (result[0] != null) return;
            Class<?> type = candidate.getClass();
            while (type != null && type != Object.class && result[0] == null) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!isLoadUrlCreator(method)) continue;
                    try {
                        Object load = buildLoadParams(home);
                        if (load == null) continue;
                        method.setAccessible(true);
                        Object value = method.invoke(candidate,
                                defaultArgs(method.getParameterTypes(), load));
                        if (value != null) { result[0] = value; break; }
                    } catch (Throwable ignored) {}
                }
                type = type.getSuperclass();
            }
        });
        return result[0];
    }

    private Object invokeModelCreator(Object model, Object home) {
        if (model == null || home == null) return null;
        Class<?> type = model.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) continue;
                String name = method.getName();
                if (!name.equals("openTabProgrammatically") && !name.equals("createNewTab")
                        && !name.equals("createNewTabWithIndex") && !name.equals("openNewTab")) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 0 || !Chrome145.GURL.equals(p[0].getName())) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(model, defaultArgs(p, home));
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private Object buildLoadParams(Object home) {
        try { return Reflect.construct(Reflect.cls(loader, LOAD_URL_PARAMS), home); }
        catch (Throwable ignored) {}
        String spec = ChromiumUrlAccessor.text(home);
        if (spec == null) return null;
        try { return Reflect.construct(Reflect.cls(loader, LOAD_URL_PARAMS), spec, 0); }
        catch (Throwable ignored) {}
        try { return Reflect.construct(Reflect.cls(loader, LOAD_URL_PARAMS), spec); }
        catch (Throwable ignored) { return null; }
    }

    private Object[] defaultArgs(Class<?>[] types, Object first) {
        Object[] args = new Object[types.length];
        if (types.length > 0) args[0] = first;
        boolean launchTypeAssigned = false;
        for (int i = 1; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == boolean.class) args[i] = false;
            else if (type == int.class) { args[i] = launchTypeAssigned ? 0 : 2; launchTypeAssigned = true; }
            else if (type == long.class) args[i] = 0L;
            else if (type == float.class) args[i] = 0f;
            else if (type == double.class) args[i] = 0d;
            else if (type == short.class) args[i] = (short) 0;
            else if (type == byte.class) args[i] = (byte) 0;
            else if (type == char.class) args[i] = (char) 0;
            else args[i] = null;
        }
        return args;
    }

    private Object model(Activity activity, boolean incognito) {
        if (activity == null) return null;
        Object selector = selector(activity);
        Object model = stableSelectorModel(selector, incognito);
        if (model != null) return model;
        return structuralModel(activity, incognito);
    }

    private Object selector(Activity activity) {
        try {
            Class<?> selectorType = Reflect.cls(loader, TAB_SELECTOR);
            Object selector = Reflect.findFieldValueByType(activity, selectorType);
            if (selector != null) return selector;
        } catch (Throwable ignored) {}
        if (profile.is152()) {
            try {
                Object selector = Reflect.get(activity, Chrome152.ACTIVITY_SELECTOR_FIELD);
                if (selector != null) return selector;
            } catch (Throwable ignored) {}
        }
        if (profile.is145()) {
            try {
                Object selector = Reflect.findFieldValueByType(activity,
                        Reflect.cls(loader, Chrome145.SELECTOR));
                if (selector != null) return selector;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean isTabStateInitialized(Activity activity) {
        Object selector = selector(activity);
        if (selector != null) {
            try {
                Object value = Reflect.call(selector, "isTabStateInitialized");
                if (value instanceof Boolean) return (Boolean) value;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private Object stableSelectorModel(Object selector, boolean incognito) {
        if (selector == null) return null;
        for (String name : new String[]{"getModel", "getTabModel"}) {
            try {
                Object value = Reflect.call(selector, name, incognito);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }
        if (profile.is152()) {
            try { return Reflect.call(selector, "k", incognito); } catch (Throwable ignored) {}
        } else if (profile.is145()) {
            try { return Reflect.call(selector, "l", incognito); } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object structuralModel(Activity activity, boolean incognito) {
        try {
            Class<?> tabModelType = Reflect.cls(loader, Chrome145.TAB_MODEL_API);
            final Object[] result = new Object[1];
            forEachActivityField(activity, candidate -> {
                if (result[0] != null) return;
                Class<?> type = candidate.getClass();
                while (type != null && type != Object.class && result[0] == null) {
                    for (Method method : type.getDeclaredMethods()) {
                        if (Modifier.isStatic(method.getModifiers())
                                || !tabModelType.isAssignableFrom(method.getReturnType())) continue;
                        Class<?>[] p = method.getParameterTypes();
                        if (p.length != 1 || p[0] != boolean.class) continue;
                        try {
                            method.setAccessible(true);
                            Object value = method.invoke(candidate, incognito);
                            if (value != null) { result[0] = value; break; }
                        } catch (Throwable ignored) {}
                    }
                    type = type.getSuperclass();
                }
            });
            return result[0];
        } catch (Throwable ignored) { return null; }
    }

    private int count(Object model) {
        if (model == null) return 0;
        try {
            Object value = Reflect.call(model, "getCount");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        Method structural = uniqueMethod(model.getClass(), int.class);
        if (structural != null && structural.getParameterCount() == 0) {
            try {
                Object value = structural.invoke(model);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private Object tabAt(Object model, int index) {
        if (model == null) return null;
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) {}
        try {
            Class<?> tabType = Reflect.cls(loader, TAB);
            Method method = uniqueMethod(model.getClass(), tabType, int.class);
            return method == null ? null : method.invoke(model, index);
        } catch (Throwable ignored) { return null; }
    }

    private boolean isLoadUrlCreator(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())) return false;
        if (!TAB.equals(method.getReturnType().getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length > 0 && LOAD_URL_PARAMS.equals(p[0].getName());
    }

    private Method uniqueBooleanStringMethod(String owner) {
        try {
            Class<?> type = Reflect.cls(loader, owner);
            Method found = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != boolean.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                if (found != null) return null;
                method.setAccessible(true);
                found = method;
            }
            return found;
        } catch (Throwable ignored) { return null; }
    }

    private Method uniqueMethod(Class<?> start, Class<?> returnType, Class<?>... params) {
        Class<?> type = start;
        while (type != null && type != Object.class) {
            Method found = null;
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (!returnType.isAssignableFrom(method.getReturnType())) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != params.length) continue;
                boolean matches = true;
                for (int i = 0; i < params.length; i++) {
                    if (actual[i] != params[i]) { matches = false; break; }
                }
                if (!matches) continue;
                if (found != null) return null;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                found = method;
            }
            if (found != null) return found;
            type = type.getSuperclass();
        }
        return null;
    }

    private void forEachActivityField(Activity activity, ObjectConsumer consumer) {
        if (activity == null) return;
        Set<Object> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Class<?> type = activity.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value != null && seen.add(value)) consumer.accept(value);
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
    }

    private boolean isChromeTabbedActivity(Activity activity) {
        try { return Reflect.cls(loader, Chrome145.ACTIVITY).isInstance(activity); }
        catch (Throwable ignored) { return Chrome145.ACTIVITY.equals(activity.getClass().getName()); }
    }

    private boolean hasClass(String name) {
        try { Reflect.cls(loader, name); return true; }
        catch (Throwable ignored) { return false; }
    }

    private boolean hasMethod(String owner, String name) {
        try { return !Reflect.named(Reflect.cls(loader, owner), name).isEmpty(); }
        catch (Throwable ignored) { return false; }
    }

    private interface ObjectConsumer { void accept(Object value); }
}
