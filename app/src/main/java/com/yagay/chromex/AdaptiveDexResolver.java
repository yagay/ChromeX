package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** DexKit-backed semantic resolver for Chromium builds and vendor forks. */
final class AdaptiveDexResolver {
    private static final String CACHE_ENGINE = "adaptive:engine-version";
    private static final String CACHE_HOMEPAGE = "adaptive:homepage";
    private static final String CACHE_TAB_CREATOR = "adaptive:tab-creator";
    private static final String CACHE_NATIVE_PREFIX = "adaptive:native:";
    private static final Pattern ENGINE_VERSION = Pattern.compile("\\d{2,3}\\.\\d+\\.\\d+\\.\\d+");
    private static final ConcurrentHashMap<String, Method> MEMORY = new ConcurrentHashMap<>();
    private static volatile boolean nativeLoaded;
    private static volatile boolean nativeLoadTried;

    private AdaptiveDexResolver() {}

    static String resolveProductVersion(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return "unknown";
        if (plausibleEngineVersion(runtime.versionName)) return runtime.versionName;

        Method method = memory(runtime, CACHE_ENGINE);
        if (method == null) {
            method = restoreExact(runtime, ResolverCacheClient.get(runtime, CACHE_ENGINE),
                    String.class, new Class<?>[0]);
        }
        if (method == null && canScan(runtime, hooks)) {
            for (String path : runtime.dexPaths()) {
                try (DexKitBridge bridge = DexKitBridge.create(path)) {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .name("getProductVersion")
                                    .returnType("java.lang.String")))
                            .single();
                    Method candidate = data.getMethodInstance(runtime.classLoader);
                    if (candidate.getParameterCount() == 0) {
                        method = candidate;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (method != null) {
            remember(runtime, CACHE_ENGINE, method);
            ResolverCacheClient.put(runtime, CACHE_ENGINE, encode(method));
            String value = invokeStringNoArg(method);
            if (plausibleEngineVersion(value)) {
                hooks.info("adaptive resolver: Chromium engine=" + value + " via "
                        + method.getDeclaringClass().getName() + '#' + method.getName());
                return value;
            }
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

        // Public/stable semantic names win when a build keeps them.
        for (String name : new String[]{"getHomepageUrl", "getHomepageGurl", "getHomepageGURL"}) {
            method = scanNamedHomepage(runtime, name);
            if (method != null) break;
        }

        // Stock Chromium itself R8-obfuscates HomepageManager. Preference/trace strings survive and
        // identify the owner much more reliably than short class names. Once an owner is found,
        // choose its best GURL accessor by signature rather than using the anchor helper blindly.
        if (method == null) {
            outer:
            for (String anchor : ChromiumSemanticAnchors.HOMEPAGE_STRINGS) {
                for (String path : runtime.dexPaths()) {
                    try (DexKitBridge bridge = DexKitBridge.create(path)) {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .returnType(Chrome145.GURL)
                                        .usingStrings(anchor)))
                                .single();
                        Method candidate = data.getMethodInstance(runtime.classLoader);
                        Method selected = bestHomepageGetter(candidate.getDeclaringClass());
                        if (selected != null) {
                            method = selected;
                            hooks.info("adaptive resolver: homepage owner anchored by " + anchor
                                    + " source=" + sourceLabel(runtime, path));
                            break outer;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Very old/forked builds may only expose the NTP literal. It is intentionally last because
        // modern Chromium has many unrelated NTP users and therefore this anchor is often ambiguous.
        if (method == null) {
            for (String path : runtime.dexPaths()) {
                try (DexKitBridge bridge = DexKitBridge.create(path)) {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .returnType(Chrome145.GURL)
                                    .usingStrings("chrome-native://newtab/")))
                            .single();
                    Method candidate = data.getMethodInstance(runtime.classLoader);
                    Method selected = bestHomepageGetter(candidate.getDeclaringClass());
                    if (selected != null) {
                        method = selected;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
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

    /** Resolve an R8-obfuscated ChromeTabCreator from Chromium TraceEvent strings + stable types. */
    static Method resolveTabCreator(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        Method method = memory(runtime, CACHE_TAB_CREATOR);
        if (isTabCreator(method)) return method;

        method = restoreByEncodedName(runtime, ResolverCacheClient.get(runtime, CACHE_TAB_CREATOR));
        if (isTabCreator(method)) {
            remember(runtime, CACHE_TAB_CREATOR, method);
            hooks.info("adaptive resolver cache hit: tab creator=" + encode(method));
            return method;
        }
        if (!canScan(runtime, hooks)) return null;

        outer:
        for (String anchor : ChromiumSemanticAnchors.TAB_CREATOR_STRINGS) {
            for (String path : runtime.dexPaths()) {
                try (DexKitBridge bridge = DexKitBridge.create(path)) {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .returnType(ChromiumSemanticAnchors.TAB)
                                    .usingStrings(anchor)))
                            .single();
                    Method candidate = data.getMethodInstance(runtime.classLoader);
                    Method selected = isTabCreator(candidate)
                            ? candidate : bestTabCreator(candidate.getDeclaringClass());
                    if (selected != null) {
                        method = selected;
                        hooks.info("adaptive resolver: tab creator anchored by " + anchor
                                + " -> " + encode(method)
                                + " source=" + sourceLabel(runtime, path));
                        break outer;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (method == null) return null;
        try { method.setAccessible(true); } catch (Throwable ignored) {}
        remember(runtime, CACHE_TAB_CREATOR, method);
        ResolverCacheClient.put(runtime, CACHE_TAB_CREATOR, encode(method));
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
        if (!loadNative(hooks)) return null;

        Throwable last = null;
        for (String dexPath : runtime.dexPaths()) {
            try (DexKitBridge bridge = DexKitBridge.create(dexPath)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().name(semanticName).returnType("void")))
                        .single();
                Method method = data.getMethodInstance(runtime.classLoader);
                if (!Modifier.isStatic(method.getModifiers())) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                remember(runtime, key, method);
                ResolverCacheClient.put(runtime, key, encode(method));
                hooks.info("adaptive resolver: native trampoline=" + semanticName + " -> "
                        + encode(method) + " source=" + sourceLabel(runtime, dexPath));
                return method;
            } catch (Throwable t) {
                last = t;
            }
        }

        hooks.warn("adaptive resolver: native trampoline unavailable " + semanticName
                + " :: " + (last == null ? "no dex path" : last.getClass().getSimpleName()));
        return null;
    }

    static Object singletonOwner(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())) return null;
        return singletonOwner(method.getDeclaringClass());
    }

    static Object singletonOwner(Class<?> type) {
        if (type == null) return null;
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

    private static Method scanNamedHomepage(ChromeRuntime runtime, String name) {
        for (String path : runtime.dexPaths()) {
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().name(name).returnType(Chrome145.GURL)))
                        .single();
                Method candidate = data.getMethodInstance(runtime.classLoader);
                if (isHomepageGetter(candidate)) return candidate;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method bestHomepageGetter(Class<?> owner) {
        if (owner == null) return null;
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tied = false;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!isHomepageGetter(candidate)) continue;
            String low = candidate.getName().toLowerCase(java.util.Locale.ROOT);
            int score = 0;
            if (low.contains("homepage")) score += 100;
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 1) score += 80;
            else if (p.length == 2) score += 70;
            else if (p.length == 0) score += 50;
            if (!Modifier.isStatic(candidate.getModifiers())) score += 5;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        if (best == null || tied) return null;
        try { best.setAccessible(true); } catch (Throwable ignored) {}
        return best;
    }

    private static Method bestTabCreator(Class<?> owner) {
        if (owner == null) return null;
        Method found = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!isTabCreator(candidate)) continue;
            if (found != null) return null;
            found = candidate;
        }
        if (found != null) try { found.setAccessible(true); } catch (Throwable ignored) {}
        return found;
    }

    private static String sourceLabel(ChromeRuntime runtime, String path) {
        if (runtime != null && path != null && path.equals(runtime.primaryDexPath())) return "primary";
        try {
            if (runtime != null && runtime.applicationInfo != null
                    && path.equals(runtime.applicationInfo.sourceDir)) return "base-apk";
        } catch (Throwable ignored) {}
        return "split";
    }

    private static boolean plausibleEngineVersion(String value) {
        if (value == null || !ENGINE_VERSION.matcher(value).matches()) return false;
        String[] p = value.split("\\.");
        if (p.length != 4) return false;
        try {
            int major = Integer.parseInt(p[0]);
            int build = Integer.parseInt(p[2]);
            return major >= 60 && major <= 250 && build >= 1000;
        } catch (Throwable ignored) {
            return false;
        }
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

    private static boolean isTabCreator(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())) return false;
        if (!ChromiumSemanticAnchors.TAB.equals(method.getReturnType().getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length > 0 && ChromiumSemanticAnchors.LOAD_URL_PARAMS.equals(p[0].getName());
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
        return MEMORY.get(runtime.resolverCacheKey() + ':' + key);
    }

    private static void remember(ChromeRuntime runtime, String key, Method method) {
        if (method != null) MEMORY.put(runtime.resolverCacheKey() + ':' + key, method);
    }

    private static String encode(Method method) {
        return method.getDeclaringClass().getName() + '#' + method.getName();
    }

    private static boolean canScan(ChromeRuntime runtime, HookSupport hooks) {
        return runtime != null && !runtime.dexPaths().isEmpty() && loadNative(hooks);
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
