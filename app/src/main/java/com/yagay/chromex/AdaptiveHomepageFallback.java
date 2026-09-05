package com.yagay.chromex;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Read-only homepage fallback for Chromium forks whose public homepage getter returns an empty GURL.
 *
 * <p>Chromium stores the user homepage in PrefService (the stable pref key is {@code homepage}).
 * Some forks additionally mirror policy/vendor values through SharedPreferencesManager. This helper
 * discovers both stores by stable type/signature and never writes browser preferences.</p>
 */
final class AdaptiveHomepageFallback {
    private static final String PREF_SERVICE = "org.chromium.components.prefs.PrefService";
    private static final String SHARED_PREFS =
            "org.chromium.base.shared_preferences.SharedPreferencesManager";
    private static final String[] PREF_SERVICE_KEYS = {
            "homepage",
            "homepage_url",
            "homepage_custom_uri"
    };
    private static final String[] SHARED_PREF_KEYS = {
            "homepage_custom_uri",
            "homepage_url",
            "homepage_uri",
            "lemur_home_page",
            "home_page",
            "homepage",
            "Chrome.Policy.HomepageLocation",
            "Chrome.Policy.HomepageLocationGurl"
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

        Object manager = AdaptiveDexResolver.singletonOwner(getter.getDeclaringClass());
        if (manager == null) {
            if (hooks != null) hooks.warn("adaptive homepage fallback: owner unavailable");
            return direct;
        }

        Object fromPrefService = fromPrefService(manager, runtime, hooks);
        if (usableGurl(fromPrefService)) return fromPrefService;

        Object fromSharedPrefs = fromSharedPreferences(manager, runtime, hooks);
        return usableGurl(fromSharedPrefs) ? fromSharedPrefs : direct;
    }

    private static Object fromPrefService(Object manager, ChromeRuntime runtime, HookSupport hooks) {
        try {
            Class<?> prefsType = Reflect.cls(runtime.classLoader, PREF_SERVICE);
            Object prefs = Reflect.findFieldValueByType(manager, prefsType);
            if (prefs == null) return null;

            Method readString = uniqueStringReader(prefsType);
            if (readString == null) {
                if (hooks != null) hooks.warn("adaptive homepage fallback: PrefService string reader ambiguous");
                return null;
            }
            try { readString.setAccessible(true); } catch (Throwable ignored) {}

            for (String key : PREF_SERVICE_KEYS) {
                Object raw = readString.invoke(prefs, key);
                if (!(raw instanceof String)) continue;
                String url = ((String) raw).trim();
                if (!looksLikeHomepage(url)) continue;
                Object gurl = buildGurl(runtime.classLoader, url);
                if (!usableGurl(gurl)) continue;
                if (hooks != null) {
                    hooks.info("adaptive homepage fallback resolved from PrefService key=" + key
                            + " reader=" + readString.getName());
                }
                return gurl;
            }
        } catch (Throwable t) {
            if (hooks != null) hooks.warn("adaptive homepage PrefService fallback unavailable: "
                    + t.getClass().getSimpleName());
        }
        return null;
    }

    private static Object fromSharedPreferences(Object manager, ChromeRuntime runtime,
                                                HookSupport hooks) {
        try {
            Class<?> prefsType = Reflect.cls(runtime.classLoader, SHARED_PREFS);
            Object shared = Reflect.findFieldValueByType(manager, prefsType);
            if (shared == null) return null;

            Method readString = Reflect.exact(prefsType, "readString", String.class, String.class);
            for (String key : SHARED_PREF_KEYS) {
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
            if (hooks != null) hooks.warn("adaptive homepage SharedPreferences fallback unavailable: "
                    + t.getClass().getSimpleName());
        }
        return null;
    }

    /** Returns the only instance String(String) accessor, independent of R8 method names. */
    private static Method uniqueStringReader(Class<?> type) {
        Method found = null;
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != String.class) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                if (found != null) return null;
                found = method;
            }
            current = current.getSuperclass();
        }
        return found;
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
