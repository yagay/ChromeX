package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

final class DownloadDialogHooks {
    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;

    DownloadDialogHooks(XposedModule module, HookSupport hooks,
                        SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        // Install every feature independently. A changed/missing dialog in a new Chrome release
        // must never prevent the other five hooks from being registered.
        hookDangerous();
        hookInsecure();
        hookDuplicate();
        hookPolicy();
        hookLocation();
        hookOpen();
    }

    private void hookDangerous() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                "showDialog", "chromex:download:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)) return chain.proceed();
                    try {
                        // Current Chromium removed/changed some display-only parameters over time,
                        // but WindowAndroid remains arg 0 and the download guid remains arg 1.
                        if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                            return chain.proceed();
                        }
                        Object bridge = chain.getThisObject();
                        String guid = (String) chain.getArg(1);
                        long ptr = nativePtr(bridge);
                        if (ptr == 0L) return chain.proceed();

                        if (!tryJni("org.chromium.chrome.browser.download.DangerousDownloadDialogBridgeJni",
                                "accepted", ptr, guid)) {
                            nativeCall("VJO",
                                    new Class<?>[]{int.class, long.class, Object.class},
                                    124, ptr, guid);
                        }
                        hooks.info("dangerous download dialog bypassed");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("dangerous dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookInsecure() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex:download:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)) return chain.proceed();
                    try {
                        if (chain.getArgs().size() < 4 || !(chain.getArg(3) instanceof Number)) {
                            return chain.proceed();
                        }
                        Object bridge = chain.getThisObject();
                        long ptr = nativePtr(bridge);
                        long callbackId = ((Number) chain.getArg(3)).longValue();
                        if (ptr == 0L) return chain.proceed();

                        if (!tryJni("org.chromium.chrome.browser.download.InsecureDownloadDialogBridgeJni",
                                "onConfirmed", ptr, callbackId, Boolean.TRUE)) {
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    3, ptr, callbackId, true);
                        }
                        hooks.info("insecure download dialog bypassed");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("insecure dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookDuplicate() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex:download:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE)) return chain.proceed();
                    try {
                        if (chain.getArgs().isEmpty()) return chain.proceed();
                        Object last = chain.getArg(chain.getArgs().size() - 1);
                        if (!(last instanceof Number)) return chain.proceed();
                        Object bridge = chain.getThisObject();
                        long ptr = nativePtr(bridge);
                        long callbackId = ((Number) last).longValue();
                        if (ptr == 0L) return chain.proceed();

                        if (!tryJni("org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni",
                                "onConfirmed", ptr, callbackId, Boolean.TRUE)) {
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    2, ptr, callbackId, true);
                        }
                        hooks.info("duplicate download dialog bypassed");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("duplicate dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookPolicy() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex:download:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                    try {
                        String guid = firstString(chain.getArgs().toArray());
                        if (guid == null) return chain.proceed();
                        Object bridge = chain.getThisObject();
                        long ptr = nativePtr(bridge);
                        if (ptr == 0L) return chain.proceed();

                        // Keep the Chrome 145 native callback as a fallback. This hook is isolated,
                        // so a future policy-dialog change cannot disable other download features.
                        nativeCall("VJO",
                                new Class<?>[]{int.class, long.class, Object.class},
                                129, ptr, guid);
                        hooks.info("policy warning download dialog bypassed");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("policy dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookLocation() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex:download:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    try {
                        Object bridge = chain.getThisObject();
                        String suggestedPath = lastString(chain.getArgs().toArray());
                        if (suggestedPath == null) suggestedPath = "";

                        // Stable Chromium callback. false means the location was selected without
                        // explicit user confirmation, which maps to continue-without-confirmation.
                        try {
                            Reflect.call(bridge, "onDownloadLocationDialogComplete",
                                    suggestedPath, Boolean.FALSE);
                            hooks.info("download location dialog bypassed via stable callback");
                            return null;
                        } catch (Throwable ignored) {}

                        // Chrome 145 fallback.
                        try {
                            Method confirm = Reflect.exact(bridge.getClass(), "b",
                                    String.class, boolean.class);
                            confirm.invoke(bridge, suggestedPath, false);
                            hooks.info("download location dialog bypassed via legacy callback");
                            return null;
                        } catch (Throwable ignored) {}

                        return chain.proceed();
                    } catch (Throwable t) {
                        hooks.error("location dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookOpen() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex:download:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    try {
                        String path = lastString(chain.getArgs().toArray());
                        if (path == null) return chain.proceed();
                        Object bridge = chain.getThisObject();
                        long ptr = nativePtr(bridge);
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJOZ",
                                new Class<?>[]{int.class, long.class, String.class, boolean.class},
                                15, ptr, path, false);
                        hooks.info("open-file confirmation bypassed");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("open dialog callback", t);
                        return chain.proceed();
                    }
                });
    }

    /**
     * Resolve a native bridge pointer without depending on R8's old field name "a". Bridge classes
     * normally contain one non-static long pointer; if several candidates exist we fail safe.
     */
    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            long value = Reflect.getLong(bridge, "a");
            if (value != 0L) return value;
        } catch (Throwable ignored) {}

        Field found = null;
        Class<?> c = bridge.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                if (found != null) return 0L;
                field.setAccessible(true);
                found = field;
            }
            c = c.getSuperclass();
        }
        if (found == null) return 0L;
        try {
            return found.getLong(bridge);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean tryJni(String className, String methodName, Object... args) {
        try {
            Class<?> jniClass = Reflect.cls(loader, className);
            Object instance = Reflect.callStatic(jniClass, "get");
            if (instance == null) return false;
            Reflect.call(instance, methodName, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object nativeCall(String methodName, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        Class<?> n = Reflect.cls(loader, Chrome145.NATIVE);
        Method method = Reflect.exact(n, methodName, parameterTypes);
        return method.invoke(null, args);
    }

    private static String firstString(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String) return (String) arg;
        }
        return null;
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }
}
