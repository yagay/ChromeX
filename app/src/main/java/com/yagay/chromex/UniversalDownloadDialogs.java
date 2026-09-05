package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Universal Chromium download-dialog binding.
 *
 * <p>Resolution order is generated JNI -> semantic JNI -> structural Java callback -> verified
 * exact fallback. No vendor package names or vendor R8 symbols are encoded here.</p>
 */
final class UniversalDownloadDialogs {
    private static final String DANGEROUS =
            "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge";
    private static final String INSECURE =
            "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge";
    private static final String DUPLICATE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String POLICY =
            "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge";
    private static final String LOCATION =
            "org.chromium.chrome.browser.download.DownloadDialogBridge";
    private static final String OPEN =
            "org.chromium.chrome.browser.download.OpenDownloadDialogBridge";

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;

    UniversalDownloadDialogs(ChromiumProfile profile, ChromeRuntime runtime,
                             HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = runtime.classLoader;
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
        if (!hasMethod(DANGEROUS, "showDialog")) return;
        hooks.all(loader, DANGEROUS, "showDialog", "chromex:universal:dialog:dangerous", chain -> {
            if (!Config.get(prefs, Config.BYPASS_DANGEROUS)) return chain.proceed();
            String guid = firstString(chain.getArgs().toArray());
            long ptr = nativePtr(chain.getThisObject());
            if (guid == null || ptr == 0L) return chain.proceed();

            if (invokeGenerated("org.chromium.chrome.browser.download.DangerousDownloadDialogBridgeJni",
                    new String[]{"accepted", "onAccepted"}, ptr, guid)
                    || invokeSemantic(
                    "org_chromium_chrome_browser_download_DangerousDownloadDialogBridge_accepted",
                    ptr, guid)
                    || invokeVerifiedNative("VJO",
                    new Class<?>[]{int.class, long.class, Object.class},
                    dangerousSelector(), ptr, guid)) {
                hooks.info("dangerous download accepted through universal dialog binding");
                return null;
            }
            hooks.warn("dangerous callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private void hookInsecure() {
        if (!hasMethod(INSECURE, "showDialog")) return;
        hooks.all(loader, INSECURE, "showDialog", "chromex:universal:dialog:insecure", chain -> {
            if (!Config.get(prefs, Config.BYPASS_INSECURE)) return chain.proceed();
            Long callback = lastLong(chain.getArgs().toArray());
            long ptr = nativePtr(chain.getThisObject());
            if (callback == null || ptr == 0L) return chain.proceed();

            if (invokeGenerated("org.chromium.chrome.browser.download.InsecureDownloadDialogBridgeJni",
                    new String[]{"onConfirmed"}, ptr, callback, true)
                    || invokeSemantic(
                    "org_chromium_chrome_browser_download_InsecureDownloadDialogBridge_onConfirmed",
                    ptr, callback, true)
                    || invokeVerifiedNative("VJJZ",
                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                    insecureSelector(), ptr, callback, true)) {
                hooks.info("insecure download accepted through universal dialog binding");
                return null;
            }
            hooks.warn("insecure callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private void hookDuplicate() {
        if (!hasMethod(DUPLICATE, "showDialog")) return;
        hooks.all(loader, DUPLICATE, "showDialog", "chromex:universal:dialog:duplicate", chain -> {
            if (!Config.get(prefs, Config.BYPASS_DUPLICATE)) return chain.proceed();
            Long callback = lastLong(chain.getArgs().toArray());
            long ptr = nativePtr(chain.getThisObject());
            if (callback == null || ptr == 0L) return chain.proceed();

            if (invokeGenerated("org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni",
                    new String[]{"onConfirmed"}, ptr, callback, true)
                    || invokeSemantic(
                    "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed",
                    ptr, callback, true)
                    || invokeVerifiedNative("VJJZ",
                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                    duplicateSelector(), ptr, callback, true)) {
                hooks.info("duplicate download accepted through universal dialog binding");
                return null;
            }
            hooks.warn("duplicate callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private void hookPolicy() {
        if (!hasMethod(POLICY, "showDialog")) return;
        hooks.all(loader, POLICY, "showDialog", "chromex:universal:dialog:policy", chain -> {
            if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
            String guid = firstString(chain.getArgs().toArray());
            long ptr = nativePtr(chain.getThisObject());
            if (guid == null || ptr == 0L) return chain.proceed();

            if (invokeGenerated(
                    "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridgeJni",
                    new String[]{"accepted", "onAccepted"}, ptr, guid)
                    || invokeSemantic(
                    "org_chromium_chrome_browser_download_PolicyWarningDownloadDialogBridge_accepted",
                    ptr, guid)
                    || invokeVerifiedNative("VJO",
                    new Class<?>[]{int.class, long.class, Object.class},
                    policySelector(), ptr, guid)) {
                hooks.info("policy warning accepted through universal dialog binding");
                return null;
            }
            hooks.warn("policy callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private void hookLocation() {
        if (!hasMethod(LOCATION, "showDialog")) return;
        hooks.all(loader, LOCATION, "showDialog", "chromex:universal:dialog:location", chain -> {
            if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
            String path = lastString(chain.getArgs().toArray());
            if (path == null) path = "";
            Object bridge = chain.getThisObject();

            Method stringBoolean = uniqueCallback(bridge, String.class, boolean.class);
            if (stringBoolean != null && invokeInstance(stringBoolean, bridge, path, false)) {
                hooks.info("download location accepted via structural String/boolean callback");
                return null;
            }
            Method oneString = uniqueCallback(bridge, String.class);
            if (oneString != null && invokeInstance(oneString, bridge, path)) {
                hooks.info("download location accepted via structural String callback");
                return null;
            }

            long ptr = nativePtr(bridge);
            Method semantic = ptr == 0L ? null : AdaptiveDexResolver.resolveSemanticNative(
                    runtime, hooks,
                    "org_chromium_chrome_browser_download_DownloadDialogBridge_onComplete");
            if (semantic != null && invokeLocationNative(semantic, ptr, bridge, path)) {
                hooks.info("download location accepted via semantic JNI binding");
                return null;
            }

            if (profile.isVerifiedExact()) {
                try {
                    Reflect.call(bridge, "onDownloadLocationDialogComplete", path, Boolean.FALSE);
                    hooks.info("download location accepted via verified stable callback");
                    return null;
                } catch (Throwable ignored) {}
                try {
                    Reflect.call(bridge, "b", path, Boolean.FALSE);
                    hooks.info("download location accepted via verified exact callback");
                    return null;
                } catch (Throwable ignored) {}
            }

            hooks.warn("download location callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private void hookOpen() {
        if (!hasMethod(OPEN, "showDialog")) return;
        hooks.all(loader, OPEN, "showDialog", "chromex:universal:dialog:open", chain -> {
            if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
            String path = lastString(chain.getArgs().toArray());
            long ptr = nativePtr(chain.getThisObject());
            if (path == null || ptr == 0L) return chain.proceed();

            if (invokeGenerated("org.chromium.chrome.browser.download.OpenDownloadDialogBridgeJni",
                    new String[]{"onConfirmed", "accepted"}, ptr, path, true)
                    || invokeSemantic(
                    "org_chromium_chrome_browser_download_OpenDownloadDialogBridge_onConfirmed",
                    ptr, path, true)
                    || invokeVerifiedNative("VJOZ",
                    new Class<?>[]{int.class, long.class, String.class, boolean.class},
                    openSelector(), ptr, path, profile.is152())) {
                hooks.info("open-file confirmation accepted through universal dialog binding");
                return null;
            }
            hooks.warn("open-file callback unresolved; preserving browser dialog");
            return chain.proceed();
        });
    }

    private boolean invokeGenerated(String className, String[] names, Object... args) {
        try {
            Class<?> jni = Reflect.cls(loader, className);
            Object instance = Reflect.callStatic(jni, "get");
            if (instance == null) return false;
            for (String name : names) {
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
            hooks.warn("semantic JNI invocation failed " + semanticName + " :: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private boolean invokeVerifiedNative(String name, Class<?>[] params, Object... args) {
        if (!profile.isVerifiedExact()) return false;
        try {
            Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
            Method method = Reflect.exact(nativeClass, name, params);
            method.invoke(null, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Method uniqueCallback(Object bridge, Class<?>... params) {
        if (bridge == null) return null;
        Method method = Reflect.signature(bridge.getClass(), void.class, params);
        if (method == null || "showDialog".equals(method.getName())
                || "destroy".equals(method.getName())) return null;
        return method;
    }

    private static boolean invokeInstance(Method method, Object owner, Object... args) {
        try {
            method.invoke(owner, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeLocationNative(Method method, long ptr, Object bridge, String path) {
        Class<?>[] p = method.getParameterTypes();
        try {
            if (p.length == 3 && p[0] == long.class && p[2] == String.class) {
                if (p[1] == boolean.class) method.invoke(null, ptr, false, path);
                else method.invoke(null, ptr, bridge, path);
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

    private boolean hasMethod(String className, String methodName) {
        try {
            return !Reflect.named(Reflect.cls(loader, className), methodName).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int dangerousSelector() { return profile.is152() ? Chrome152.DANGEROUS_ACCEPT : 124; }
    private int insecureSelector() { return profile.is152() ? Chrome152.INSECURE_ACCEPT : 3; }
    private int duplicateSelector() { return profile.is152() ? Chrome152.DUPLICATE_ACCEPT : 2; }
    private int policySelector() { return profile.is152() ? Chrome152.POLICY_ACCEPT : 129; }
    private int openSelector() { return profile.is152() ? Chrome152.OPEN_ACCEPT : 15; }

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
            if (args[i] instanceof Number) return ((Number) args[i]).longValue();
        }
        return null;
    }
}
