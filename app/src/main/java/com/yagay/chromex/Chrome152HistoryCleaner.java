package com.yagay.chromex;

import android.app.Activity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Verified Chrome 152.0.7977.75 recently-closed cleanup sequence. */
final class Chrome152HistoryCleaner {
    private static final int CLOSE_WINDOW_SOURCE_RECENT_TABS = 4;
    private static final int NATIVE_CLEAR_RECENTLY_CLOSED = 135;

    private final ClassLoader loader;
    private final HookSupport hooks;

    Chrome152HistoryCleaner(ClassLoader loader, HookSupport hooks) {
        this.loader = loader;
        this.hooks = hooks;
    }

    boolean clear(Activity activity, String reason) {
        if (activity == null) return false;
        try {
            Object manager = Reflect.get(activity, Chrome152.ACTIVITY_RECENTLY_CLOSED_FIELD);
            if (manager == null) {
                hooks.warn("Chrome 152 recently-closed manager unavailable at " + reason);
                return false;
            }
            boolean result = clearManager(manager);
            if (result) hooks.info("Chrome 152 recently-closed history truly cleared at " + reason);
            return result;
        } catch (Throwable t) {
            hooks.error("Chrome 152 recently-closed clear at " + reason, t);
            return false;
        }
    }

    private boolean clearManager(Object manager) throws ReflectiveOperationException {
        Object multiInstanceManager = Reflect.get(manager, "c");
        if (multiInstanceManager != null) {
            Object rawInstances = Reflect.call(multiInstanceManager, "k");
            ArrayList<Integer> ids = new ArrayList<>();
            if (rawInstances instanceof List<?>) {
                for (Object info : (List<?>) rawInstances) {
                    if (info == null) continue;
                    try { ids.add(Reflect.getInt(info, "a")); } catch (Throwable ignored) {}
                }
            }
            Reflect.call(multiInstanceManager, "f", CLOSE_WINDOW_SOURCE_RECENT_TABS, ids);
        }

        Object bridge = Reflect.get(manager, "d");
        if (bridge == null) return false;
        long nativePtr = Reflect.getLong(bridge, "a");
        if (nativePtr == 0L) return false;
        Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
        Method clearNative = Reflect.exact(nativeClass, "VJ", int.class, long.class);
        clearNative.invoke(null, NATIVE_CLEAR_RECENTLY_CLOSED, nativePtr);

        try {
            Object windowCache = Reflect.get(manager, "f");
            if (windowCache instanceof Map<?, ?>) ((Map<?, ?>) windowCache).clear();
        } catch (Throwable ignored) {}
        return true;
    }
}
