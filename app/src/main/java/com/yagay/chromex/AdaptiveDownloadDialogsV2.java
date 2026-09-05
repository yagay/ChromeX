package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Structural dialog bypasses for vendor Chromium builds. Semantic JNI trampoline names are
 * resolved from dex; hashed J.N names and numeric selectors are never hard-coded here.
 */
final class AdaptiveDownloadDialogsV2 {
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    AdaptiveDownloadDialogsV2(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
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
                "showDialog", "chromex:adaptive2:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)) return chain.proceed();
                    String guid = firstString(chain.getArgs().toArray());
                    long ptr = nativePtr(chain.getThisObject());
                    if (guid == null || ptr == 0L) return chain.proceed();

                    if (invokeGeneratedJni("org.chromium.chrome.browser.download.DangerousDownloadDialogBridgeJni",
                            new String[]{"accepted", "onAccepted"}, ptr, guid)
                            || invokeSemantic(
                            "org_chromium_chrome_browser_download_DangerousDownloadDialogBridge_accepted",
                            ptr, guid)) {
                        hooks.info("adaptive dangerous download accepted via semantic JNI");
                        return null;
                    }
                    hooks.warn("adaptive dangerous callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private void hookInsecure() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex:adaptive2:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    Long callback = lastLong(chain.getArgs().toArray());
                    if (ptr == 0L || callback == null) return chain.proceed();

                    if (invokeGeneratedJni("org.chromium.chrome.browser.download.InsecureDownloadDialogBridgeJni",
                            new String[]{"onConfirmed"}, ptr, callback, true)
                            || invokeSemantic(
                            "org_chromium_chrome_browser_download_InsecureDownloadDialogBridge_onConfirmed",
                            ptr, callback, true)) {
                        hooks.info("adaptive insecure download accepted via semantic JNI");
                        return null;
                    }
                    hooks.warn("adaptive insecure callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private void hookDuplicate() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex:adaptive2:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE)) return chain.proceed();
                    long ptr = nativePtr(chain.getThisObject());
                    Long callback = lastLong(chain.getArgs().toArray());
                    if (ptr == 0L || callback == null) return chain.proceed();

                    if (invokeGeneratedJni("org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni",
                            new String[]{"onConfirmed"}, ptr, callback, true)
                            || invokeSemantic(
                            "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed",
                            ptr, callback, true)) {
                        hooks.info("adaptive duplicate download accepted via semantic JNI");
                        return null;
                    }
                    hooks.warn("adaptive duplicate callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private void hookPolicy() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex:adaptive2:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                    String guid = firstString(chain.getArgs().toArray());
                    long ptr = nativePtr(chain.getThisObject());
                    if (guid == null || ptr == 0L) return chain.proceed();

                    if (invokeGeneratedJni(
                            "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridgeJni",
                            new String[]{"accepted", "onAccepted"}, ptr, guid)
                            || invokeSemantic(
                            "org_chromium_chrome_browser_download_PolicyWarningDownloadDialogBridge_accepted",
                            ptr, guid)) {
                        hooks.info("adaptive policy warning accepted via semantic JNI");
                        return null;
                    }
                    hooks.warn("adaptive policy callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private void hookLocation() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex:adaptive2:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) path = "";
                    Object bridge = chain.getThisObject();

                    Method oneString = uniqueCallback(bridge, String.class);
                    if (oneString != null) {
                        try {
                            oneString.invoke(bridge, path);
                            hooks.info("adaptive download location accepted via one-string bridge callback");
                            return null;
                        } catch (Throwable ignored) {}
                    }

                    Method stringBoolean = uniqueCallback(bridge, String.class, boolean.class);
                    if (stringBoolean != null) {
                        try {
                            stringBoolean.invoke(bridge, path, false);
                            hooks.info("adaptive download location accepted via string/boolean callback");
                            return null;
                        } catch (Throwable ignored) {}
                    }

                    long ptr = nativePtr(bridge);
                    if (ptr != 0L) {
                        Method nativeMethod = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks,
                                "org_chromium_chrome_browser_download_DownloadDialogBridge_onComplete");
                        if (nativeMethod != null && invokeLocationNative(nativeMethod, ptr, bridge, path)) {
                            hooks.info("adaptive download location accepted via semantic JNI trampoline");
                            return null;
                        }
                    }

                    hooks.warn("adaptive location callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private void hookOpen() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex:adaptive2:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    long ptr = nativePtr(chain.getThisObject());
                    if (path == null || ptr == 0L) return chain.proceed();

                    if (invokeGeneratedJni("org.chromium.chrome.browser.download.OpenDownloadDialogBridgeJni",
                            new String[]{"onConfirmed", "accepted"}, ptr, path, true)
                            || invokeSemantic(
                            "org_chromium_chrome_browser_download_OpenDownloadDialogBridge_onConfirmed",
                            ptr, path, true)) {
                        hooks.info("adaptive open-file confirmation accepted via semantic JNI");
                        return null;
                    }
                    hooks.warn("adaptive open-file callback unresolved; keeping browser dialog");
                    return chain.proceed();
                });
    }

    private boolean invokeGeneratedJni(String className, String[] methodNames, Object... args) {
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

    private boolean invokeSemantic(String semanticName, Object... args) {
        Method method = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks, semanticName);
        if (method == null) return false;
        try {
            method.invoke(null, args);
            return true;
        } catch (Throwable t) {
            hooks.warn("adaptive semantic JNI invocation failed " + semanticName + " :: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private boolean invokeLocationNative(Method method, long ptr, Object bridge, String path) {
        Class<?>[] p = method.getParameterTypes();
        try {
            if (p.length == 3 && p[0] == long.class && p[2] == String.class) {
                if (p[1] == boolean.class) {
                    method.invoke(null, ptr, false, path);
                    return true;
                }
                method.invoke(null, ptr, bridge, path);
                return true;
            }
            if (p.length == 3 && p[0] == long.class && p[1] == String.class
                    && p[2] == boolean.class) {
                method.invoke(null, ptr, path, false);
                return true;
            }
            if (p.length == 4 && p[0] == long.class && p[2] == String.class
                    && p[3] == boolean.class) {
                method.invoke(null, ptr, bridge, path, false);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Method uniqueCallback(Object bridge, Class<?>... params) {
        if (bridge == null) return null;
        Method method = Reflect.signature(bridge.getClass(), void.class, params);
        if (method == null || "showDialog".equals(method.getName())
                || "destroy".equals(method.getName())) return null;
        return method;
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
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof String) return (String) arg;
        return null;
    }

    private static String lastString(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }

    private static Long lastLong(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
        }
        return null;
    }
}
