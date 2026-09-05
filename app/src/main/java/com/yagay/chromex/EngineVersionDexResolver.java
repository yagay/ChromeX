package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Semantic DEX fallback for Chromium's product version, independent of APK versionName. */
final class EngineVersionDexResolver {
    private EngineVersionDexResolver() {}

    static String resolve(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        for (String path : runtime.dexPaths()) {
            if (path == null || path.isBlank()) continue;
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .name("getProductVersion")
                                .returnType("java.lang.String")))
                        .single();
                Method method = data.getMethodInstance(runtime.classLoader);
                if (method.getParameterCount() != 0) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                Object owner = Modifier.isStatic(method.getModifiers()) ? null
                        : AdaptiveDexResolver.singletonOwner(method.getDeclaringClass());
                if (!Modifier.isStatic(method.getModifiers()) && owner == null) continue;
                Object value = method.invoke(owner);
                if (value instanceof String
                        && ChromiumEngineVersionScanner.plausible((String) value)) {
                    if (hooks != null) hooks.info("Chromium engine version bound from semantic DEX: "
                            + value + " via " + method.getDeclaringClass().getName()
                            + '#' + method.getName());
                    return (String) value;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
