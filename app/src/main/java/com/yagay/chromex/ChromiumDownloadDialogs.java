package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Shared verified download-dialog bypasses; profiles contribute only JNI selector differences. */
final class ChromiumDownloadDialogs {
    private final ChromiumProfile profile;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    ChromiumDownloadDialogs(ChromiumProfile profile, ClassLoader loader,
                            HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.loader = loader;
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
        hooks.all(loader, "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                "showDialog", "chromex:download:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)
                            || chain.getArgs().size() < 2
                            || !(chain.getArg(1) instanceof String)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        Object guid = chain.getArg(1);
                        if (!tryJni("org.chromium.chrome.browser.download.DangerousDownloadDialogBridgeJni",
                                "accepted", ptr, guid)) {
                            nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                    dangerousSelector(), ptr, guid);
                        }
                        hooks.info(profile.label() + " dangerous download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error(profile.label() + " dangerous confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookInsecure() {
        hooks.all(loader, "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex:download:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)
                            || chain.getArgs().size() < 4
                            || !(chain.getArg(3) instanceof Number)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        long callback = ((Number) chain.getArg(3)).longValue();
                        if (!tryJni("org.chromium.chrome.browser.download.InsecureDownloadDialogBridgeJni",
                                "onConfirmed", ptr, callback, Boolean.TRUE)) {
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    insecureSelector(), ptr, callback, true);
                        }
                        hooks.info(profile.label() + " insecure download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error(profile.label() + " insecure confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookDuplicate() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex:download:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object last = chain.getArg(chain.getArgs().size() - 1);
                    if (!(last instanceof Number)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        long callback = ((Number) last).longValue();
                        if (!tryJni("org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni",
                                "onConfirmed", ptr, callback, Boolean.TRUE)) {
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    duplicateSelector(), ptr, callback, true);
                        }
                        hooks.info(profile.label() + " duplicate download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error(profile.label() + " duplicate confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookPolicy() {
        hooks.all(loader, "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex:download:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                    String guid = firstString(chain.getArgs().toArray());
                    if (guid == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                policySelector(), ptr, guid);
                        hooks.info(profile.label() + " policy warning confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error(profile.label() + " policy confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookLocation() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex:download:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) path = "";
                    Object bridge = chain.getThisObject();
                    try {
                        Reflect.call(bridge, "onDownloadLocationDialogComplete", path, Boolean.FALSE);
                        hooks.info(profile.label() + " location accepted via stable callback");
                        return null;
                    } catch (Throwable ignored) {}
                    try {
                        Reflect.call(bridge, "b", path, Boolean.FALSE);
                        hooks.info(profile.label() + " location accepted via exact callback");
                        return null;
                    } catch (Throwable t) {
                        hooks.warn(profile.label() + " location callback unavailable: "
                                + t.getClass().getSimpleName());
                        return chain.proceed();
                    }
                });
    }

    private void hookOpen() {
        hooks.all(loader, "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex:download:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJOZ",
                                new Class<?>[]{int.class, long.class, String.class, boolean.class},
                                openSelector(), ptr, path, profile.is152());
                        hooks.info(profile.label() + " open-file confirmation accepted automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error(profile.label() + " open-file confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private int dangerousSelector() { return profile.is152() ? Chrome152.DANGEROUS_ACCEPT : 124; }
    private int insecureSelector() { return profile.is152() ? Chrome152.INSECURE_ACCEPT : 3; }
    private int duplicateSelector() { return profile.is152() ? Chrome152.DUPLICATE_ACCEPT : 2; }
    private int policySelector() { return profile.is152() ? Chrome152.POLICY_ACCEPT : 129; }
    private int openSelector() { return profile.is152() ? Chrome152.OPEN_ACCEPT : 15; }

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

    private Object nativeCall(String name, Class<?>[] params, Object... args)
            throws ReflectiveOperationException {
        Class<?> n = Reflect.cls(loader, Chrome145.NATIVE);
        Method method = Reflect.exact(n, name, params);
        return method.invoke(null, args);
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
