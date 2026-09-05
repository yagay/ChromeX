package com.yagay.chromex;

import java.lang.reflect.Method;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Structural DexKit resolver with per-Chrome-build persistent symbol caching. */
final class DexKitResolver {
    private static final String CACHE_HOMEPAGE = "homepage";
    private static final String CACHE_DOWNLOAD_MESSAGE = "download-message";
    private static volatile boolean nativeLoaded;
    private static volatile boolean nativeLoadTried;

    private DexKitResolver() {}

    static Method resolveHomepageGetter(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        Method cached = restore(runtime, ResolverCacheClient.get(runtime, CACHE_HOMEPAGE),
                new Class<?>[]{boolean.class}, Chrome145.GURL);
        if (cached != null) {
            hooks.info("resolver cache hit: homepage="
                    + cached.getDeclaringClass().getName() + "#" + cached.getName());
            return cached;
        }
        if (runtime.chromeSplitPath == null || !loadNative(hooks)) return null;
        try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
            MethodData data = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("boolean")
                            .returnType(Chrome145.GURL)
                            .usingStrings("chrome-native://newtab/")))
                    .single();
            Method method = data.getMethodInstance(runtime.classLoader);
            ResolverCacheClient.put(runtime, CACHE_HOMEPAGE, encode(method));
            hooks.info("resolver scan: homepage="
                    + method.getDeclaringClass().getName() + "#" + method.getName());
            return method;
        } catch (Throwable t) {
            hooks.warn("resolver: homepage getter not uniquely resolved: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    static Method resolveDownloadMessageMethod(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        try {
            Class<?> offlineItem = Reflect.cls(runtime.classLoader, Chrome145.OFFLINE_ITEM);
            Method cached = restore(runtime,
                    ResolverCacheClient.get(runtime, CACHE_DOWNLOAD_MESSAGE),
                    new Class<?>[]{offlineItem, boolean.class, boolean.class, boolean.class},
                    null);
            if (cached != null) {
                hooks.info("resolver cache hit: download-message="
                        + cached.getDeclaringClass().getName() + "#" + cached.getName());
                return cached;
            }
        } catch (Throwable ignored) {}

        if (runtime.chromeSplitPath == null || !loadNative(hooks)) return null;
        try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
            MethodData clue = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("Download.Progress.InfoBar.Shown")))
                    .single();
            Method clueMethod = clue.getMethodInstance(runtime.classLoader);
            Class<?> owner = clueMethod.getDeclaringClass();
            Class<?> offlineItem = Reflect.cls(runtime.classLoader, Chrome145.OFFLINE_ITEM);
            for (Method m : owner.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == offlineItem
                        && p[1] == boolean.class && p[2] == boolean.class
                        && p[3] == boolean.class) {
                    m.setAccessible(true);
                    ResolverCacheClient.put(runtime, CACHE_DOWNLOAD_MESSAGE, encode(m));
                    hooks.info("resolver scan: download-message="
                            + owner.getName() + "#" + m.getName());
                    return m;
                }
            }
            hooks.warn("resolver: download message owner found but callback signature missing: "
                    + owner.getName());
            return null;
        } catch (Throwable t) {
            hooks.warn("resolver: download message not uniquely resolved: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private static Method restore(ChromeRuntime runtime, String encoded,
                                  Class<?>[] params, String returnTypeName) {
        if (encoded == null || encoded.isBlank()) return null;
        int hash = encoded.lastIndexOf('#');
        if (hash <= 0 || hash + 1 >= encoded.length()) return null;
        try {
            Class<?> owner = Reflect.cls(runtime.classLoader, encoded.substring(0, hash));
            String name = encoded.substring(hash + 1);
            Method method = Reflect.exact(owner, name, params);
            if (returnTypeName != null && !returnTypeName.equals(method.getReturnType().getName())) {
                return null;
            }
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String encode(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static boolean loadNative(HookSupport hooks) {
        if (nativeLoaded) return true;
        if (nativeLoadTried) return false;
        synchronized (DexKitResolver.class) {
            if (nativeLoaded) return true;
            if (nativeLoadTried) return false;
            nativeLoadTried = true;
            try {
                System.loadLibrary("dexkit");
                nativeLoaded = true;
                hooks.info("resolver: DexKit native runtime loaded");
                return true;
            } catch (Throwable t) {
                hooks.warn("resolver: DexKit native runtime unavailable: "
                        + t.getClass().getSimpleName());
                return false;
            }
        }
    }
}
