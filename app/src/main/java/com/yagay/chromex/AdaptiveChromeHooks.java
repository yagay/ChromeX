package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * Conservative hooks for Chrome builds without a verified release profile. This path deliberately
 * avoids every Chrome-145/152 R8 short name. Stable APIs are used directly and DexKit is used only
 * for distinctive structural lookups. Unsupported features fail independently instead of risking
 * a hook on an unrelated obfuscated class.
 */
final class AdaptiveChromeHooks {
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ChromeRuntime runtime;
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
        installStableProgrammaticNewTabRedirect();
        installTranslateSuppression();

        // Resolve this eagerly for diagnostics/cache evolution even when Toast replacement is off.
        DexKitResolver.resolveDownloadMessageMethod(runtime, hooks);
    }

    private void installNoRestoreBySignature() {
        try {
            Class<?> command = Reflect.cls(runtime.classLoader, "org.chromium.base.CommandLine");
            List<Method> candidates = new ArrayList<>();
            for (Method m : command.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != boolean.class) continue;
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
                        && "no-restore-state".equals(chain.getArg(0))) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            hooks.error("adaptive no-restore", t);
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
            return getter.invoke(owner, false);
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

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String name : new String[]{"getSpec", "j"}) {
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
