package com.yagay.chromex;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Optional binding for Chromium builds that still expose DownloadManagerService#getAllDownloads.
 *
 * <p>This is capability-driven rather than Chrome-specific. Older/standard download backends often
 * require a fresh history fetch after an external same-name normalization so their UI rebuilds from
 * the authoritative download record. Newer/vendor backends that do not expose getAllDownloads are
 * left untouched.</p>
 */
final class DownloadBackendRefreshBinding {
    private static final long REFRESH_DELAY_MS = 120L;

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Class<?> serviceType;
    private Method refreshMethod;

    DownloadBackendRefreshBinding(ChromeRuntime runtime, HookSupport hooks) {
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void install() {
        try {
            serviceType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            refreshMethod = resolveRefreshMethod(serviceType);
        } catch (Throwable ignored) {
            serviceType = null;
            refreshMethod = null;
        }
        if (refreshMethod == null) {
            hooks.info("download backend refresh capability unavailable; observer/history bindings only");
            return;
        }
        DownloadNormalizationRegistry.setListener((oldPath, newPath) ->
                main.postDelayed(() -> refresh(oldPath, newPath), REFRESH_DELAY_MS));
        hooks.info("download backend refresh capability bound: "
                + refreshMethod.getDeclaringClass().getName() + '#' + refreshMethod.getName());
    }

    private void refresh(String oldPath, String newPath) {
        Method method = refreshMethod;
        if (method == null) return;
        Object service = currentService();
        if (service == null) {
            hooks.warn("download backend refresh skipped: service unavailable");
            return;
        }
        try {
            Object argument = defaultArgument(method.getParameterTypes()[0]);
            method.invoke(service, argument);
            hooks.info("download backend refresh requested after normalization: "
                    + fileName(oldPath) + " -> " + fileName(newPath));
        } catch (Throwable t) {
            hooks.warn("download backend refresh failed: " + t.getClass().getSimpleName());
        }
    }

    private Object currentService() {
        if (serviceType == null) return null;
        try {
            Object service = Reflect.callStatic(serviceType, "getDownloadManagerService");
            if (service != null) return service;
        } catch (Throwable ignored) {}
        try { return AdaptiveDexResolver.singletonOwner(serviceType); }
        catch (Throwable ignored) { return null; }
    }

    private static Method resolveRefreshMethod(Class<?> type) {
        if (type == null) return null;
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!candidate(method)) continue;
            if (found != null) return prefer(found, method);
            found = method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!candidate(method)) continue;
            if (found != null && sameSignature(found, method)) continue;
            if (found != null) return prefer(found, method);
            try { method.setAccessible(true); } catch (Throwable ignored) {}
            found = method;
        }
        return found;
    }

    private static boolean candidate(Method method) {
        return method != null
                && "getAllDownloads".equals(method.getName())
                && !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 1;
    }

    private static Method prefer(Method a, Method b) {
        Class<?> ap = a.getParameterTypes()[0];
        Class<?> bp = b.getParameterTypes()[0];
        if (isBoolean(bp) && !isBoolean(ap)) return b;
        return a;
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName())
                && a.getParameterTypes()[0] == b.getParameterTypes()[0];
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static Object defaultArgument(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return Boolean.FALSE;
        if (!type.isPrimitive()) return null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return Boolean.FALSE;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "<none>";
        try { return new File(path).getName(); }
        catch (Throwable ignored) { return "<value>"; }
    }
}
