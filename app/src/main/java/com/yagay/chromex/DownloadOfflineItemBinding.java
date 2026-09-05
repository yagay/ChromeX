package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves Chromium's DownloadItem -> OfflineItem materializer without relying on its R8 name. */
final class DownloadOfflineItemBinding {
    private DownloadOfflineItemBinding() {}

    static Method resolve(ClassLoader loader) {
        if (loader == null) return null;
        try {
            Class<?> item = Reflect.cls(loader, ChromiumSemanticAnchors.DOWNLOAD_ITEM);
            Class<?> offline = Reflect.cls(loader, ChromiumSemanticAnchors.OFFLINE_ITEM);

            Method named = null;
            for (Method method : item.getDeclaredMethods()) {
                if (!"createOfflineItem".equals(method.getName()) || !matches(method, item, offline)) {
                    continue;
                }
                if (named != null) return null;
                named = method;
            }
            if (named != null) {
                try { named.setAccessible(true); } catch (Throwable ignored) {}
                return named;
            }

            Method structural = null;
            for (Method method : item.getDeclaredMethods()) {
                if (!matches(method, item, offline)) continue;
                if (structural != null) return null;
                structural = method;
            }
            if (structural != null) {
                try { structural.setAccessible(true); } catch (Throwable ignored) {}
            }
            return structural;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean matches(Method method, Class<?> item, Class<?> offline) {
        if (method == null || !Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != offline) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && p[0] == item;
    }
}
