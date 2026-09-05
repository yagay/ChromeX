package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Safe download-dialog bypasses for unknown Chrome builds. No numeric J.N selector is used. */
final class AdaptiveDownloadDialogs {
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    AdaptiveDownloadDialogs(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        hookDangerous();
        hookInsecure();
        hookDuplicate();
        hookPolicy();
        hookLocation();
        hookOpen();
    }

    private void hookDangerous() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                "showDialog", "chromex:adaptive:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)
                            || chain.getArgs().size() < 2
                            || !(chain.getArg(1) instanceof String)) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    String guid = (String) chain.getArg(1);
                    if (ptr != 0L && invokeJni(
                            "org.chromium.chrome.browser.download.DangerousDownloadDialogBridgeJni",
                            new String[]{"accepted", "onAccepted"}, ptr, guid)) {
                        hooks.info("adaptive dangerous download accepted via semantic JNI wrapper");
                        return null;
                    }
                    hooks.warn("adaptive dangerous callback unavailable; keeping Chrome dialog");
                    return chain.proceed();
                });
    }

    private void hookInsecure() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex:adaptive:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)
                            || chain.getArgs().size() < 4
                            || !(chain.getArg(3) instanceof Number)) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    long callback = ((Number) chain.getArg(3)).longValue();
                    if (ptr != 0L && invokeJni(
                            "org.chromium.chrome.browser.download.InsecureDownloadDialogBridgeJni",
                            new String[]{"onConfirmed"}, ptr, callback, true)) {
                        hooks.info("adaptive insecure download accepted via semantic JNI wrapper");
                        return null;
                    }
                    hooks.warn("adaptive insecure callback unavailable; keeping Chrome dialog");
                    return chain.proceed();
                });
    }

    private void hookDuplicate() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex:adaptive:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object value = chain.getArg(chain.getArgs().size() - 1);
                    if (!(value instanceof Number)) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    long callback = ((Number) value).longValue();
                    if (ptr != 0L && invokeJni(
                            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni",
                            new String[]{"onConfirmed"}, ptr, callback, true)) {
                        hooks.info("adaptive duplicate download accepted via semantic JNI wrapper");
                        return null;
                    }
                    hooks.warn("adaptive duplicate callback unavailable; keeping Chrome dialog");
                    return chain.proceed();
                });
    }

    private void hookPolicy() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex:adaptive:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                    String guid = firstString(chain.getArgs().toArray());
                    if (guid == null) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    if (ptr != 0L && invokeJni(
                            "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridgeJni",
                            new String[]{"accepted", "onAccepted"}, ptr, guid)) {
                        hooks.info("adaptive policy warning accepted via semantic JNI wrapper");
                        return null;
                    }
                    hooks.warn("adaptive policy callback unavailable; keeping Chrome dialog");
                    return chain.proceed();
                });
    }

    private void hookLocation() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex:adaptive:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) path = "";
                    Method callback = uniqueStringBooleanCallback(chain.getThisObject());
                    if (callback == null) {
                        hooks.warn("adaptive location callback unresolved; keeping Chrome dialog");
                        return chain.proceed();
                    }
                    try {
                        callback.invoke(chain.getThisObject(), path, false);
                        hooks.info("adaptive download location accepted via bridge callback");
                        return null;
                    } catch (Throwable t) {
                        hooks.warn("adaptive location callback failed: " + t.getClass().getSimpleName());
                        return chain.proceed();
                    }
                });
    }

    private void hookOpen() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex:adaptive:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    if (ptr != 0L && invokeJni(
                            "org.chromium.chrome.browser.download.OpenDownloadDialogBridgeJni",
                            new String[]{"onConfirmed", "accepted"}, ptr, path, true)) {
                        hooks.info("adaptive open-file confirmation accepted via semantic JNI wrapper");
                        return null;
                    }
                    hooks.warn("adaptive open-file callback unavailable; keeping Chrome dialog");
                    return chain.proceed();
                });
    }

    private Method uniqueStringBooleanCallback(Object bridge) {
        if (bridge == null) return null;
        // Override-aware resolution prevents a child override plus its superclass declaration from
        // being incorrectly treated as two ambiguous callbacks.
        Method method = Reflect.signature(bridge.getClass(), void.class,
                String.class, boolean.class);
        if (method != null && !"showDialog".equals(method.getName())) return method;
        return null;
    }

    private boolean invokeJni(String className, String[] methodNames, Object... args) {
        try {
            Class<?> jni = Reflect.cls(runtime.classLoader, className);
            Object instance = Reflect.callStatic(jni, "get");
            if (instance == null) return false;
            for (String name : methodNames) {
                try {
                    Reflect.call(instance, name, args);
                    return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
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

    private static String firstString(Object[] args) {
        for (Object arg : args) if (arg instanceof String) return (String) arg;
        return null;
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }
}
