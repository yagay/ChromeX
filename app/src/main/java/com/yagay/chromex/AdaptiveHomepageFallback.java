package com.yagay.chromex;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Read-only homepage fallback for Chromium forks whose public homepage getter returns an empty GURL.
 *
 * <p>Some vendors keep their custom homepage in Chromium's SharedPreferencesManager rather than in
 * the stock homepage PrefService. This helper deliberately discovers that manager by stable type and
 * reads a short allow-list of homepage-shaped keys. It never writes browser preferences.</p>
 */
final class AdaptiveHomepageFallback {
    private static final String SHARED_PREFS =
            "org.chromium.base.shared_preferences.SharedPreferencesManager";
    private static final String[] KEYS = {
            "homepage_custom_uri",
            "homepage_url",
            "homepage_uri",
            "lemur_home_page",
            "home_page",
            "homepage"
    };

    private AdaptiveHomepageFallback() {}

    static Object resolve(Method getter, ChromeRuntime runtime, HookSupport hooks,
                          boolean forZeroTabs) {
        Object direct = invokeGetter(getter, forZeroTabs);
        return fallbackAfterDirect(direct, getter, runtime, hooks);
    }

    static Object fallbackAfterDirect(Object direct, Method getter,
                                      ChromeRuntime runtime, HookSupport hooks) {
        if (usableGurl(direct)) return direct;
        if (getter == null || runtime == null) return direct;
        try {
            Object manager = AdaptiveDexResolver.singletonOwner(getter.getDeclaringClass());
            if (manager == null) return direct;
            Class<?> prefsType = Reflect.cls(runtime.classLoader, SHARED_PREFS);
            Object shared = Reflect.findFieldValueByType(manager, prefsType);
            if (shared == null) return direct;

            Method readString = Reflect.exact(prefsType, "readString", String.class, String.class);
            for (String key : KEYS) {
                Object value = readString.invoke(shared, key, "");
                if (!(value instanceof String)) continue;
                String url = ((String) value).trim();
                if (!looksLikeHomepage(url)) continue;
                Object gurl = buildGurl(runtime.classLoader, url);
                if (!usableGurl(gurl)) continue;
                if (hooks != null) {
                    hooks.info("adaptive homepage fallback resolved from Chromium preference key="
                            + key);
                }
                return gurl;
            }
        } catch (Throwable t) {
            if (hooks != null) hooks.warn("adaptive homepage fallback unavailable: "
                    + t.getClass().getSimpleName());
        }
        return direct;
    }

    private static Object invokeGetter(Method getter, boolean forZeroTabs) {
        if (getter == null) return null;
        try {
            Object owner = Modifier.isStatic(getter.getModifiers())
                    ? null : AdaptiveDexResolver.singletonOwner(getter);
            if (!Modifier.isStatic(getter.getModifiers()) && owner == null) return null;
            Class<?>[] p = getter.getParameterTypes();
            if (p.length == 0) return getter.invoke(owner);
            if (p.length == 1 && p[0] == boolean.class) return getter.invoke(owner, false);
            if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                return getter.invoke(owner, false, forZeroTabs);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object buildGurl(ClassLoader loader, String value) {
        try {
            Class<?> gurl = Reflect.cls(loader, Chrome145.GURL);
            return Reflect.construct(gurl, value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean usableGurl(Object gurl) {
        String value = gurlText(gurl);
        return looksLikeHomepage(value);
    }

    static String gurlText(Object gurl) {
        if (gurl == null) return null;
        for (String name : new String[]{"getSpec", "e", "g", "d", "f", "b", "toString"}) {
            try {
                Object value = Reflect.call(gurl, name);
                if (value instanceof String && !((String) value).isBlank()) return (String) value;
            } catch (Throwable ignored) {}
        }
        Class<?> c = gurl.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(gurl);
                    if (value instanceof String && !((String) value).isBlank()) return (String) value;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean looksLikeHomepage(String value) {
        if (value == null || value.isBlank()) return false;
        String low = value.trim().toLowerCase(java.util.Locale.ROOT);
        return low.startsWith("https://") || low.startsWith("http://")
                || low.startsWith("chrome://") || low.startsWith("chrome-native://")
                || low.startsWith("about:");
    }
}
