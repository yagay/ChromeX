package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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

        // HomepageManager's public/internal resolver is structurally (boolean, boolean) -> GURL.
        // A previous implementation cached the private/static one-boolean NTP helper because it
        // contains "chrome-native://newtab/". Restore only the two-boolean resolver so that stale
        // caches automatically miss and are rebuilt.
        Method cached = restore(runtime, ResolverCacheClient.get(runtime, CACHE_HOMEPAGE),
                new Class<?>[]{boolean.class, boolean.class}, Chrome145.GURL);
        if (cached != null && !Modifier.isStatic(cached.getModifiers())) {
            hooks.info("resolver cache hit: homepage="
                    + cached.getDeclaringClass().getName() + "#" + cached.getName());
            return cached;
        }
        if (runtime.chromeSplitPath == null || !loadNative(hooks)) return null;

        try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
            // First locate the NTP helper only as a semantic anchor for HomepageManager's owner.
            // In Chrome 152 this is w5c.e(boolean), while the actual homepage resolver is the
            // non-static w5c.b(boolean, boolean). R8 may rename both in later builds.
            MethodData ntpHelper = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("boolean")
                            .returnType(Chrome145.GURL)
                            .usingStrings("chrome-native://newtab/")))
                    .single();
            Method anchor = ntpHelper.getMethodInstance(runtime.classLoader);
            Class<?> owner = anchor.getDeclaringClass();

            Method resolved = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (!Chrome145.GURL.equals(method.getReturnType().getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || params[0] != boolean.class
                        || params[1] != boolean.class) continue;
                if (resolved != null) {
                    hooks.warn("resolver: homepage owner has ambiguous two-boolean GURL methods: "
                            + owner.getName());
                    return null;
                }
                method.setAccessible(true);
                resolved = method;
            }
            if (resolved == null) {
                hooks.warn("resolver: homepage owner found but resolver signature missing: "
                        + owner.getName());
                return null;
            }

            ResolverCacheClient.put(runtime, CACHE_HOMEPAGE, encode(resolved));
            hooks.info("resolver scan: homepage="
                    + resolved.getDeclaringClass().getName() + "#" + resolved.getName());
            return resolved;
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
