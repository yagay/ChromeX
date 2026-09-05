package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * Narrow runtime corrections for Chrome 152.0.7977.x.
 *
 * Keep this class small: it intentionally layers only fixes for signatures that compile-time
 * checks cannot validate against Chrome's runtime DEX.
 */
final class Chrome152Corrections {
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;

    Chrome152Corrections(XposedModule module, HookSupport hooks,
                         SharedPreferences prefs, ClassLoader loader) {
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        installPreciseNewTabRedirect();
        installOpenDialogCorrection();
    }

    private void installPreciseNewTabRedirect() {
        // Verified in 152.0.7977.75: iq4#m(...) is the primary ChromeTabCreator#createNewTab path
        // and LoadUrlParams.a contains the URL. This precise hook supplements the broader profile
        // and uses GURL.getSpec() first, which is retained in this build.
        hooks.all(loader, Chrome152.TAB_CREATOR, "m",
                "chromex152:correction:newtab-m", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object params = chain.getArg(0);
                    if (params == null || !Chrome145.LOAD_URL_PARAMS.equals(params.getClass().getName())) {
                        return chain.proceed();
                    }
                    try {
                        Object raw = Reflect.get(params, Chrome152.LOAD_URL_FIELD);
                        if (!(raw instanceof String) || !isNtp((String) raw)) return chain.proceed();
                        String home = homepage();
                        if (home != null && !home.isBlank() && !isNtp(home)) {
                            Reflect.set(params, Chrome152.LOAD_URL_FIELD, home);
                            hooks.info("Chrome 152 precise new-tab redirect applied");
                        }
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 precise new-tab redirect", t);
                    }
                    return chain.proceed();
                });
    }

    private void installOpenDialogCorrection() {
        // Verified from Chrome 152.0.7977.75 DEX:
        // J.N.VJOZ(int selector, long nativePtr, String path, boolean confirmed)
        // selector 9 + true means accept/open. The generic profile previously used Object.class
        // for the third reflective parameter, which cannot resolve the actual String signature.
        hooks.all(loader,
                "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex152:correction:open-dialog", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
                        Method callback = Reflect.exact(nativeClass, "VJOZ",
                                int.class, long.class, String.class, boolean.class);
                        callback.invoke(null, Chrome152.OPEN_ACCEPT, ptr, path, true);
                        hooks.info("Chrome 152 open-file confirmation accepted via corrected signature");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 corrected open-file callback", t);
                        return chain.proceed();
                    }
                });
    }

    private String homepage() {
        try {
            Class<?> manager = Reflect.cls(loader, Chrome152.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            if (instance == null) return Chrome145.NTP;
            Object gurl = Reflect.call(instance, "e", Boolean.FALSE);
            if (gurl == null) return Chrome145.NTP;
            for (String method : new String[]{"getSpec", "j"}) {
                try {
                    Object value = Reflect.call(gurl, method);
                    if (value instanceof String) return (String) value;
                } catch (Throwable ignored) {}
            }
            try {
                Object value = Reflect.get(gurl, "a");
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            hooks.warn("Chrome 152 corrected homepage lookup unavailable: "
                    + t.getClass().getSimpleName());
        }
        return Chrome145.NTP;
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            long value = Reflect.getLong(bridge, "a");
            if (value != 0L) return value;
        } catch (Throwable ignored) {}

        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                if (found != null) return 0L;
                field.setAccessible(true);
                found = field;
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try {
            return found.getLong(bridge);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }
}
