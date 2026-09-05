package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * Conservative hooks for Chrome builds without a verified release profile. This path deliberately
 * avoids release-specific R8 short names. Stable APIs are used directly and DexKit is used only
 * for distinctive structural lookups. Unsupported features fail independently.
 */
final class AdaptiveChromeHooks {
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ChromeRuntime runtime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Object>> models = new ArrayList<>();
    private Method homepageGetter;

    AdaptiveChromeHooks(XposedModule module, HookSupport hooks,
                        SharedPreferences prefs, ChromeRuntime runtime) {
        this.hooks = hooks;
        this.prefs = prefs;
        this.runtime = runtime;
    }

    void install() {
        hooks.info("adaptive resolver profile active for Chrome " + runtime.versionName);
        installNoRestoreBySignature();
        homepageGetter = DexKitResolver.resolveHomepageGetter(runtime, hooks);
        installActivityLifecycle();
        installStableProgrammaticNewTabRedirect();
        installTranslateSuppression();
        new AdaptiveDownloadObserver(runtime, hooks, prefs).install();
    }

    private void installNoRestoreBySignature() {
        try {
            Class<?> command = Reflect.cls(runtime.classLoader, "org.chromium.base.CommandLine");
            List<Method> candidates = new ArrayList<>();
            for (Method m : command.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    m.setAccessible(true);
                    candidates.add(m);
                }
            }
            if (candidates.size() != 1) {
                hooks.warn("adaptive no-restore unresolved: bool(String) candidates="
                        + candidates.size());
                return;
            }
            hooks.method(candidates.get(0), "chromex:adaptive:no-restore", chain -> {
                if (Config.get(prefs, Config.CLEAN_START)
                        && "no-restore-state".equals(chain.getArg(0))) return Boolean.TRUE;
                return chain.proceed();
            });
        } catch (Throwable t) {
            hooks.error("adaptive no-restore", t);
        }
    }

    private void installActivityLifecycle() {
        hooks.exact(runtime.classLoader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex:adaptive:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) {
                        Activity activity = (Activity) receiver;
                        captureModelsFromActivity(activity);
                        if (Config.get(prefs, Config.CLEAN_START)) {
                            Intent intent = activity.getIntent();
                            if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                                    && intent.getData() == null) {
                                main.postDelayed(() -> settleColdStart(activity), 1400L);
                            }
                        }
                    }
                    return result;
                });

        hooks.exact(runtime.classLoader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex:adaptive:onDestroy", chain -> {
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity && ((Activity) receiver).isFinishing()
                            && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                        captureModelsFromActivity((Activity) receiver);
                        closeAllKnownTabs();
                    }
                    return chain.proceed();
                });
    }

    /**
     * Find the selector structurally instead of depending on fields such as O2 or classes such as
     * k3r. A selector candidate must expose a unique (boolean) -> TabModel method.
     */
    private void captureModelsFromActivity(Activity activity) {
        if (activity == null) return;
        try {
            Class<?> tabModelApi = Reflect.cls(runtime.classLoader, Chrome145.TAB_MODEL_API);
            Class<?> c = activity.getClass();
            while (c != null && c != Object.class) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Object candidate;
                    try {
                        field.setAccessible(true);
                        candidate = field.get(activity);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (candidate == null) continue;
                    Method selector = findModelSelector(candidate.getClass(), tabModelApi);
                    if (selector == null) continue;
                    try {
                        Object regular = selector.invoke(candidate, false);
                        if (regular != null) remember(regular);
                    } catch (Throwable ignored) {}
                    try {
                        Object incognito = selector.invoke(candidate, true);
                        if (incognito != null) remember(incognito);
                    } catch (Throwable ignored) {}
                    if (!knownModels().isEmpty()) {
                        hooks.info("adaptive TabModel selector resolved structurally: "
                                + candidate.getClass().getName() + "#" + selector.getName());
                        return;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            hooks.warn("adaptive TabModel selector unresolved: " + t.getClass().getSimpleName());
        }
    }

    private Method findModelSelector(Class<?> type, Class<?> tabModelApi) {
        Method found = null;
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method method : c.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != boolean.class) continue;
                if (!tabModelApi.isAssignableFrom(method.getReturnType())) continue;
                if (found != null) return null;
                method.setAccessible(true);
                found = method;
            }
            c = c.getSuperclass();
        }
        return found;
    }

    private void settleColdStart(Activity activity) {
        captureModelsFromActivity(activity);
        Object regular = regularModel();
        Object home = homepageGurl();
        if (regular == null || home == null) return;
        try {
            String wanted = gurlText(home);
            Object keep = null;
            int total = count(regular);
            for (int i = 0; i < total; i++) {
                Object tab = tabAt(regular, i);
                if (tab == null) continue;
                String url = gurlText(Reflect.call(tab, "getUrl"));
                if ((wanted != null && wanted.equals(url)) || (isNtp(wanted) && isNtp(url))) {
                    keep = tab;
                    break;
                }
            }
            if (keep == null) {
                keep = Reflect.call(regular, "openTabProgrammatically", home, 2, Boolean.FALSE);
            }
            if (keep == null) return;
            for (Object model : knownModels()) {
                for (int i = count(model) - 1; i >= 0; i--) {
                    Object tab = tabAt(model, i);
                    if (tab != null && tab != keep) closeTab(model, tab);
                }
            }
            hooks.info("adaptive cold-start tab cleanup applied");
        } catch (Throwable t) {
            hooks.warn("adaptive cold-start cleanup unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void installStableProgrammaticNewTabRedirect() {
        if (homepageGetter == null) return;
        hooks.all(runtime.classLoader, Chrome145.TAB_MODEL, "openTabProgrammatically",
                "chromex:adaptive:newtab", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object gurl = chain.getArg(0);
                    if (!isNtp(gurlText(gurl))) return chain.proceed();
                    Object homepage = homepageGurl();
                    if (homepage == null || isNtp(gurlText(homepage))) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = homepage;
                    hooks.info("adaptive new-tab redirect applied");
                    return chain.proceed(args);
                });
    }

    private Object homepageGurl() {
        Method getter = homepageGetter;
        if (getter == null) return null;
        try {
            Object owner = null;
            if (!Modifier.isStatic(getter.getModifiers())) {
                Class<?> type = getter.getDeclaringClass();
                Method factory = null;
                for (Method m : type.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                            || m.getReturnType() != type) continue;
                    if (factory != null) return null;
                    m.setAccessible(true);
                    factory = m;
                }
                if (factory == null) return null;
                owner = factory.invoke(null);
            }
            // Structural resolver is (incognito, forZeroTabs) -> GURL.
            return getter.invoke(owner, false, false);
        } catch (Throwable t) {
            hooks.warn("adaptive homepage invocation failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void installTranslateSuppression() {
        hooks.all(runtime.classLoader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex:adaptive:translate-create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
        hooks.all(runtime.classLoader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex:adaptive:translate-show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
    }

    private void remember(Object model) {
        if (model == null) return;
        synchronized (models) {
            Iterator<WeakReference<Object>> it = models.iterator();
            while (it.hasNext()) {
                Object old = it.next().get();
                if (old == null) it.remove();
                else if (old == model) return;
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

    private Object regularModel() {
        for (Object model : knownModels()) {
            try {
                Object value = Reflect.call(model, "isIncognito");
                if (value instanceof Boolean && !((Boolean) value)) return model;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Object tabAt(Object model, int index) {
        try {
            return Reflect.call(model, "getTabAt", index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void closeTab(Object model, Object tab) {
        try {
            Class<?> tabType = Reflect.cls(runtime.classLoader,
                    "org.chromium.chrome.browser.tab.Tab");
            Reflect.exact(model.getClass(), "closeTab", tabType).invoke(model, tab);
        } catch (Throwable ignored) {}
    }

    private void closeAllKnownTabs() {
        for (Object model : knownModels()) {
            try {
                Reflect.call(model, "forceCloseAllTabs");
                continue;
            } catch (Throwable ignored) {}
            for (int i = count(model) - 1; i >= 0; i--) {
                Object tab = tabAt(model, i);
                if (tab != null) closeTab(model, tab);
            }
        }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String name : new String[]{"getSpec", "j", "n"}) {
            try {
                Object value = Reflect.call(gurl, name);
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

    private boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }
}
