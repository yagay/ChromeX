package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Single semantic duplicate-conflict hook used only while same-name overwrite is enabled.
 *
 * <p>It deliberately does not guess or vacate a filesystem directory. Chromium is allowed to
 * reserve whatever temporary/uniquified target its backend requires; the completion coordinator
 * later normalizes the committed artifact using its authoritative file/content identity.</p>
 */
final class UniversalOverwriteDuplicateHook {
    private static final String BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String SEMANTIC =
            "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed";

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    UniversalOverwriteDuplicateHook(ChromiumProfile profile, ChromeRuntime runtime,
                                    HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, BRIDGE);
            if (Reflect.named(type, "showDialog").isEmpty()) return;
        } catch (Throwable ignored) {
            return;
        }

        hooks.all(runtime.classLoader, BRIDGE, "showDialog",
                "chromex:overwrite:duplicate-unified", chain -> {
                    if (!Config.stored(prefs, Config.OVERWRITE_DUPLICATE)) {
                        return chain.proceed();
                    }
                    if (Config.stored(prefs, Config.OVERWRITE_CONFIRM_DUPLICATE)) {
                        hooks.info("same-name overwrite: keeping Chromium duplicate confirmation");
                        return chain.proceed();
                    }

                    Object bridge = chain.getThisObject();
                    Long callback = lastLong(chain.getArgs().toArray());
                    long ptr = nativePtr(bridge);
                    if (callback == null || ptr == 0L || !confirm(bridge, ptr, callback)) {
                        hooks.warn("same-name overwrite: duplicate callback unresolved; preserving dialog");
                        return chain.proceed();
                    }
                    hooks.info("same-name overwrite: duplicate auto-confirmed by unified semantic hook");
                    return null;
                });
        hooks.info("unified overwrite duplicate hook installed");
    }

    private boolean confirm(Object bridge, long ptr, long callback) {
        if (invokeGenerated(ptr, callback)) return true;
        Method semantic = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks, SEMANTIC);
        if (semantic != null) {
            try {
                semantic.invoke(null, ptr, callback, true);
                return true;
            } catch (Throwable ignored) {}
        }
        if (profile.isVerifiedExact()) {
            try {
                Class<?> nativeClass = Reflect.cls(runtime.classLoader, Chrome145.NATIVE);
                Method method = Reflect.exact(nativeClass, "VJJZ",
                        int.class, long.class, long.class, boolean.class);
                int selector = profile.is152() ? Chrome152.DUPLICATE_ACCEPT : 2;
                method.invoke(null, selector, ptr, callback, true);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean invokeGenerated(long ptr, long callback) {
        try {
            Class<?> jni = Reflect.cls(runtime.classLoader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni");
            Object instance = Reflect.callStatic(jni, "get");
            if (instance == null) return false;
            for (String name : new String[]{"onConfirmed", "confirmed"}) {
                try {
                    Reflect.call(instance, name, ptr, callback, true);
                    return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        if (profile.isVerifiedExact()) {
            try {
                long value = Reflect.getLong(bridge, "a");
                if (value != 0L) return value;
            } catch (Throwable ignored) {}
        }
        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (value == 0L) continue;
                    if (found != null) return 0L;
                    found = field;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try { return found.getLong(bridge); }
        catch (Throwable ignored) { return 0L; }
    }

    private static Long lastLong(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
            if (args[i] instanceof Number) return ((Number) args[i]).longValue();
        }
        return null;
    }
}
