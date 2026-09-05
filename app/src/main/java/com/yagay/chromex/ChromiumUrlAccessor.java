package com.yagay.chromex;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/** Semantic GURL/string access independent of R8 member names. */
final class ChromiumUrlAccessor {
    private ChromiumUrlAccessor() {}

    static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String) return usable((String) value) ? (String) value : null;

        for (String name : new String[]{"getSpec", "getUrl", "getURL", "toString"}) {
            try {
                Method method = Reflect.exact(value.getClass(), name);
                if (method.getParameterCount() != 0 || method.getReturnType() != String.class) continue;
                Object raw = method.invoke(value);
                if (raw instanceof String && usable((String) raw)) return (String) raw;
            } catch (Throwable ignored) {}
        }

        Class<?> type = value.getClass();
        String only = null;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(value);
                    if (!(raw instanceof String) || !usable((String) raw)) continue;
                    String text = (String) raw;
                    if (looksLikeUrl(text)) return text;
                    if (only != null && !only.equals(text)) return null;
                    only = text;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return only;
    }

    static Object buildGurl(ClassLoader loader, String text) {
        if (!usable(text) || loader == null) return null;
        try {
            return Reflect.construct(Reflect.cls(loader, Chrome145.GURL), text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isNtp(Object value) {
        return isNtp(text(value));
    }

    static boolean isNtp(String value) {
        if (value == null) return false;
        String low = value.trim().toLowerCase(Locale.ROOT);
        return low.startsWith("chrome-native://newtab")
                || low.startsWith("chrome://newtab")
                || low.equals("about:newtab");
    }

    static boolean looksLikeUrl(String value) {
        if (!usable(value)) return false;
        String low = value.trim().toLowerCase(Locale.ROOT);
        return low.startsWith("https://") || low.startsWith("http://")
                || low.startsWith("chrome://") || low.startsWith("chrome-native://")
                || low.startsWith("about:") || low.startsWith("file://")
                || low.startsWith("content://");
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank() && value.length() < 8192;
    }
}
