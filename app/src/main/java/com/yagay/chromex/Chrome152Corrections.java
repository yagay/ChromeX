package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * One narrow runtime correction that remains separate until the Chrome 152 profile is regenerated:
 * OpenDownloadDialogBridge's J.N.VJOZ third parameter is String, not Object. No other method is
 * duplicated here.
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

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }
}
