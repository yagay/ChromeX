package com.yagay.chromex;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** DexKit-backed structural resolver for unknown Chromium and vendor forks. */
final class AdaptiveDexResolver {
    private static final String CACHE_ENGINE = "adaptive:engine-version";
    private static final String CACHE_ENGINE_LITERAL = "adaptive:engine-literal";
    private static final String CACHE_HOMEPAGE = "adaptive:homepage";
    private static final String CACHE_NATIVE_PREFIX = "adaptive:native:";
    private static final Pattern ENGINE_VERSION = Pattern.compile("\\d{2,3}\\.\\d+\\.\\d+\\.\\d+");
    private static final ConcurrentHashMap<String, Method> MEMORY = new ConcurrentHashMap<>();
    private static volatile boolean nativeLoaded;
    private static volatile boolean nativeLoadTried;

    private AdaptiveDexResolver() {}

    static String resolveProductVersion(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return "unknown";
        if (isPlausibleEngineVersion(runtime.versionName)) return runtime.versionName;

        String cachedLiteral = ResolverCacheClient.get(runtime, CACHE_ENGINE_LITERAL);
        if (isPlausibleEngineVersion(cachedLiteral)) {
            hooks.info("adaptive resolver cache hit: Chromium engine=" + cachedLiteral);
            return cachedLiteral;
        }

        Method method = memory(runtime, CACHE_ENGINE);
        if (method == null) {
            method = restoreExact(runtime, ResolverCacheClient.get(runtime, CACHE_ENGINE),
                    String.class, new Class<?>[0]);
        }
        if (method == null && canScan(runtime, hooks)) {
            try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .name("getProductVersion")
                                .returnType("java.lang.String")))
                        .single();
                Method candidate = data.getMethodInstance(runtime.classLoader);
                if (candidate.getParameterCount() == 0) method = candidate;
            } catch (Throwable t) {
                hooks.warn("adaptive resolver: getProductVersion unavailable: "
                        + t.getClass().getSimpleName());
            }
        }
        if (method != null) {
            remember(runtime, CACHE_ENGINE, method);
            ResolverCacheClient.put(runtime, CACHE_ENGINE, encode(method));
            String value = invokeStringNoArg(method);
            if (isPlausibleEngineVersion(value)) {
                ResolverCacheClient.put(runtime, CACHE_ENGINE_LITERAL, value);
                hooks.info("adaptive resolver: Chromium engine=" + value + " via "
                        + method.getDeclaringClass().getName() + "#" + method.getName());
                return value;
            }
        }

        String literal = scanEngineVersionLiteral(runtime, hooks);
        if (literal != null) {
            ResolverCacheClient.put(runtime, CACHE_ENGINE_LITERAL, literal);
            return literal;
        }
        return runtime.versionName == null ? "unknown" : runtime.versionName;
    }

    static Method resolveHomepageGetter(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        Method method = memory(runtime, CACHE_HOMEPAGE);
        if (isHomepageGetter(method)) return method;

        method = restoreHomepage(runtime, ResolverCacheClient.get(runtime, CACHE_HOMEPAGE));
        if (isHomepageGetter(method)) {
            remember(runtime, CACHE_HOMEPAGE, method);
            hooks.info("adaptive resolver cache hit: homepage=" + encode(method));
            return method;
        }
        if (!canScan(runtime, hooks)) return null;

        for (String name : new String[]{"getHomepageUrl", "getHomepageGurl", "getHomepageGURL"}) {
            try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .name(name)
                                .returnType(Chrome145.GURL)))
                        .single();
                Method candidate = data.getMethodInstance(runtime.classLoader);
                if (isHomepageGetter(candidate)) {
                    method = candidate;
                    break;
                }
            } catch (Throwable ignored) {}
        }

        // Newer Chromium builds can obfuscate the method name while retaining the NTP constant.
        if (method == null) {
            try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType(Chrome145.GURL)
                                .usingStrings("chrome-native://newtab/")))
                        .single();
                Method candidate = data.getMethodInstance(runtime.classLoader);
                if (isHomepageGetter(candidate)) method = candidate;
                if (method == null) {
                    Class<?> owner = candidate.getDeclaringClass();
                    for (Method declared : owner.getDeclaredMethods()) {
                        if (isHomepageGetter(declared)) {
                            if (method != null) {
                                method = null;
                                break;
                            }
                            method = declared;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (method == null) {
            hooks.warn("adaptive resolver: homepage getter unresolved");
            return null;
        }
        try { method.setAccessible(true); } catch (Throwable ignored) {}
        remember(runtime, CACHE_HOMEPAGE, method);
        ResolverCacheClient.put(runtime, CACHE_HOMEPAGE, encode(method));
        hooks.info("adaptive resolver: homepage=" + encode(method)
                + " params=" + method.getParameterCount());
        return method;
    }

    static Method resolveSemanticNative(ChromeRuntime runtime, HookSupport hooks, String semanticName) {
        if (runtime == null || semanticName == null || semanticName.isBlank()) return null;
        String key = CACHE_NATIVE_PREFIX + semanticName;
        Method cached = memory(runtime, key);
        if (cached != null && Modifier.isStatic(cached.getModifiers())) return cached;

        cached = restoreByName(runtime, ResolverCacheClient.get(runtime, key), semanticName);
        if (cached != null && Modifier.isStatic(cached.getModifiers())) {
            remember(runtime, key, cached);
            return cached;
        }
        if (!canScan(runtime, hooks)) return null;

        try (DexKitBridge bridge = DexKitBridge.create(runtime.chromeSplitPath)) {
            MethodData data = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().name(semanticName).returnType("void")))
                    .single();
            Method method = data.getMethodInstance(runtime.classLoader);
            if (!Modifier.isStatic(method.getModifiers())) return null;
            try { method.setAccessible(true); } catch (Throwable ignored) {}
            remember(runtime, key, method);
            ResolverCacheClient.put(runtime, key, encode(method));
            hooks.info("adaptive resolver: native trampoline=" + semanticName + " -> " + encode(method));
            return method;
        } catch (Throwable t) {
            hooks.warn("adaptive resolver: native trampoline unavailable " + semanticName
                    + " :: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static Object singletonOwner(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())) return null;
        Class<?> type = method.getDeclaringClass();
        Method found = null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != 0
                    || candidate.getReturnType() != type) continue;
            if (found != null) return null;
            try { candidate.setAccessible(true); } catch (Throwable ignored) {}
            found = candidate;
        }
        if (found == null) return null;
        try { return found.invoke(null); }
        catch (Throwable ignored) { return null; }
    }

    private static String scanEngineVersionLiteral(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || runtime.chromeSplitPath == null) return null;
        try (FileInputStream in = new FileInputStream(runtime.chromeSplitPath)) {
            String dexText = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            Matcher matcher = ENGINE_VERSION.matcher(dexText);
            String best = null;
            while (matcher.find()) {
                String candidate = matcher.group();
                if (!isPlausibleEngineVersion(candidate)) continue;
                if (best == null || compareVersion(candidate, best) > 0) best = candidate;
            }
            if (best != null) {
                hooks.info("adaptive resolver: Chromium engine=" + best
                        + " via split dex version literal");
            }
            return best;
        } catch (Throwable t) {
            hooks.warn("adaptive resolver: split dex version scan failed: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isPlausibleEngineVersion(String value) {
        if (value == null || !ENGINE_VERSION.matcher(value).matches()) return false;
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        try {
            int major = Integer.parseInt(parts[0]);
            int build = Integer.parseInt(parts[2]);
            return major >= 60 && major <= 250 && build >= 1000;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int compareVersion(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            try {
                int av = Integer.parseInt(a[i]);
                int bv = Integer.parseInt(b[i]);
                if (av != bv) return Integer.compare(av, bv);
            } catch (Throwable ignored) {}
        }
        return Integer.compare(a.length, b.length);
    }

    private static Method restoreHomepage(ChromeRuntime runtime, String encoded) {
        Method method = restoreByEncodedName(runtime, encoded);
        return isHomepageGetter(method) ? method : null;
    }

    private static Method restoreExact(ChromeRuntime runtime, String encoded,
                                       Class<?> returnType, Class<?>[] params) {
        Method method = restoreByEncodedName(runtime, encoded);
        if (method == null || method.getReturnType() != returnType) return null;
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != params.length) return null;
        for (int i = 0; i < actual.length; i++) if (actual[i] != params[i]) return null;
        return method;
    }

    private static Method restoreByName(ChromeRuntime runtime, String encoded, String expectedName) {
        Method method = restoreByEncodedName(runtime, encoded);
        return method != null && expectedName.equals(method.getName()) ? method : null;
    }

    private static Method restoreByEncodedName(ChromeRuntime runtime, String encoded) {
        if (runtime == null || encoded == null || encoded.isBlank()) return null;
        int hash = encoded.lastIndexOf('#');
        if (hash <= 0 || hash + 1 >= encoded.length()) return null;
        try {
            Class<?> owner = Reflect.cls(runtime.classLoader, encoded.substring(0, hash));
            String name = encoded.substring(hash + 1);
            Method found = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!name.equals(method.getName())) continue;
                if (found != null) return null;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                found = method;
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isHomepageGetter(Method method) {
        if (method == null || !Chrome145.GURL.equals(method.getReturnType().getName())) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length > 2) return false;
        for (Class<?> param : params) if (param != boolean.class) return false;
        return true;
    }

    private static String invokeStringNoArg(Method method) {
        if (method == null || method.getParameterCount() != 0) return null;
        try {
            Object owner = Modifier.isStatic(method.getModifiers()) ? null : singletonOwner(method);
            if (!Modifier.isStatic(method.getModifiers()) && owner == null) return null;
            Object result = method.invoke(owner);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method memory(ChromeRuntime runtime, String key) {
        return MEMORY.get(runtime.resolverCacheKey() + ":" + key);
    }

    private static void remember(ChromeRuntime runtime, String key, Method method) {
        if (method != null) MEMORY.put(runtime.resolverCacheKey() + ":" + key, method);
    }

    private static String encode(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static boolean canScan(ChromeRuntime runtime, HookSupport hooks) {
        return runtime != null && runtime.chromeSplitPath != null && loadNative(hooks);
    }

    private static boolean loadNative(HookSupport hooks) {
        if (nativeLoaded) return true;
        if (nativeLoadTried) return false;
        synchronized (AdaptiveDexResolver.class) {
            if (nativeLoaded) return true;
            if (nativeLoadTried) return false;
            nativeLoadTried = true;
            try {
                System.loadLibrary("dexkit");
                nativeLoaded = true;
                hooks.info("adaptive resolver: DexKit native runtime loaded");
                return true;
            } catch (Throwable t) {
                hooks.warn("adaptive resolver: DexKit native runtime unavailable: "
                        + t.getClass().getSimpleName());
                return false;
            }
        }
    }
}
