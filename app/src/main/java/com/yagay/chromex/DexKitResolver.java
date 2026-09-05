package com.yagay.chromex;

import java.lang.reflect.Method;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * Narrow DexKit-backed symbol resolver used only when a stable Java entry point is unavailable.
 * Results are structural: no R8 short class name is assumed. Keep queries small and distinctive.
 */
final class DexKitResolver {
    private static volatile boolean nativeLoaded;
    private static volatile boolean nativeLoadTried;

    private DexKitResolver() {}

    static Method resolveHomepageGetter(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || runtime.chromeSplitPath == null) return null;
        if (!loadNative(hooks)) return null;
        try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
            MethodData data = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("boolean")
                            .returnType(Chrome145.GURL)
                            .usingStrings("chrome-native://newtab/")))
                    .single();
            Method method = data.getMethodInstance(runtime.classLoader);
            hooks.info("resolver: homepage getter="
                    + method.getDeclaringClass().getName() + "#" + method.getName());
            return method;
        } catch (Throwable t) {
            hooks.warn("resolver: homepage getter not uniquely resolved: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    static Method resolveDownloadMessageMethod(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || runtime.chromeSplitPath == null) return null;
        if (!loadNative(hooks)) return null;
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
                if (p.length == 4
                        && p[0] == offlineItem
                        && p[1] == boolean.class
                        && p[2] == boolean.class
                        && p[3] == boolean.class) {
                    m.setAccessible(true);
                    hooks.info("resolver: download message="
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
