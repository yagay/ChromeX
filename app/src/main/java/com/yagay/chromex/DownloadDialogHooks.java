package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Method;

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
        try {
            Class<?> window = Reflect.cls(loader, Chrome145.WINDOW);
            Class<?> profile = Reflect.cls(loader, Chrome145.PROFILE);
            Class<?> otr = Reflect.cls(loader,
                    "org.chromium.chrome.browser.profiles.OtrProfileId");

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{window, String.class, String.class, long.class, String.class, int.class},
                    "chromex:download:dangerous",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_DANGEROUS)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            long ptr = Reflect.getLong(bridge, "a");
                            String guid = (String) chain.getArg(1);
                            nativeCall("VJO",
                                    new Class<?>[]{int.class, long.class, Object.class},
                                    124, ptr, guid);
                            hooks.info("dangerous download dialog bypassed");
                            return null;
                        } catch (Throwable t) {
                            hooks.error("dangerous dialog callback", t);
                            return chain.proceed();
                        }
                    });

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{window, String.class, long.class, long.class},
                    "chromex:download:insecure",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_INSECURE)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            long ptr = Reflect.getLong(bridge, "a");
                            long id = ((Number) chain.getArg(3)).longValue();
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    3, ptr, id, true);
                            hooks.info("insecure download dialog bypassed");
                            return null;
                        } catch (Throwable t) {
                            hooks.error("insecure dialog callback", t);
                            return chain.proceed();
                        }
                    });

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{window, String.class, String.class, long.class,
                            boolean.class, otr, long.class},
                    "chromex:download:duplicate",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_DUPLICATE)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            long ptr = Reflect.getLong(bridge, "a");
                            long id = ((Number) chain.getArg(6)).longValue();
                            nativeCall("VJJZ",
                                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                    2, ptr, id, true);
                            hooks.info("duplicate download dialog bypassed");
                            return null;
                        } catch (Throwable t) {
                            hooks.error("duplicate dialog callback", t);
                            return chain.proceed();
                        }
                    });

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{window, String.class, String.class},
                    "chromex:download:policy",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            long ptr = Reflect.getLong(bridge, "a");
                            String guid = (String) chain.getArg(0);
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

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.DownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{window, long.class, int.class, int.class, String.class, profile},
                    "chromex:download:location",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            String directory = "";
                            try {
                                Method resolver = Reflect.exact(bridge.getClass(), "a", profile);
                                Object value = resolver.invoke(null, chain.getArg(5));
                                if (value instanceof String) directory = (String) value;
                            } catch (Throwable ignored) {}
                            Method confirm = Reflect.exact(bridge.getClass(), "b",
                                    String.class, boolean.class);
                            confirm.invoke(bridge, directory, false);
                            hooks.info("download location dialog bypassed");
                            return null;
                        } catch (Throwable t) {
                            hooks.error("location dialog callback", t);
                            return chain.proceed();
                        }
                    });

            hooks.exact(loader,
                    "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                    "showDialog",
                    new Class<?>[]{profile, String.class},
                    "chromex:download:open",
                    chain -> {
                        if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                        try {
                            Object bridge = chain.getThisObject();
                            long ptr = Reflect.getLong(bridge, "a");
                            String path = (String) chain.getArg(1);
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
        } catch (Throwable t) {
            hooks.error("install download-dialog hooks", t);
        }
    }

    private Object nativeCall(String methodName, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        Class<?> n = Reflect.cls(loader, Chrome145.NATIVE);
        Method method = Reflect.exact(n, methodName, parameterTypes);
        return method.invoke(null, args);
    }
}
