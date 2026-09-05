package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verified Chrome 152.0.7977.75 recently-closed cleanup.
 *
 * R8 inlines RecentlyClosedEntriesManager.clearRecentlyClosedEntries() into the recent-tabs UI,
 * so there is no callable manager method left in this release. Static DEX analysis recovers the
 * exact inlined sequence:
 *   manager.c.k() -> recently closed windows
 *   manager.c.f(4, instanceIds) -> close those window records (RECENT_TABS source)
 *   J.N.VJ(135, manager.d.a) -> clear TabRestoreService recently-closed entries
 *   manager.f.clear() -> clear cached closed-window metadata
 *
 * RecentlyClosedEntriesManager.e() is updateRecentlyClosedEntries(), not clear; never use it here.
 */
final class Chrome152RecentHistoryHooks {
    private static final int CLOSE_WINDOW_SOURCE_RECENT_TABS = 4;
    private static final int NATIVE_CLEAR_RECENTLY_CLOSED = 135;
    private static final long COLD_CLEAR_DELAY_MS = 2300L;
    private static final long EXIT_CLEAR_DELAY_MS = 250L;

    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    Chrome152RecentHistoryHooks(ClassLoader loader, HookSupport hooks, SharedPreferences prefs) {
        this.loader = loader;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex152:history:cold", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (!(receiver instanceof Activity)
                            || !Config.get(prefs, Config.CLEAR_CLOSED_TABS)) return result;
                    Activity activity = (Activity) receiver;
                    Intent intent = activity.getIntent();
                    if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                            && intent.getData() == null) {
                        main.postDelayed(() -> clearFromActivity(activity, "cold-start"),
                                COLD_CLEAR_DELAY_MS);
                    }
                    return result;
                });

        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex152:history:exit", chain -> {
                    Object receiver = chain.getThisObject();
                    Activity activity = receiver instanceof Activity ? (Activity) receiver : null;
                    boolean shouldClear = activity != null && activity.isFinishing()
                            && Config.get(prefs, Config.CLEAR_CLOSED_TABS);
                    Object result = chain.proceed();
                    if (shouldClear) {
                        main.postDelayed(() -> clearFromActivity(activity, "exit"),
                                EXIT_CLEAR_DELAY_MS);
                    }
                    return result;
                });
    }

    private void clearFromActivity(Activity activity, String reason) {
        if (activity == null) return;
        try {
            Object manager = Reflect.get(activity, Chrome152.ACTIVITY_RECENTLY_CLOSED_FIELD);
            if (manager == null) {
                hooks.warn("Chrome 152 recently-closed manager unavailable at " + reason);
                return;
            }
            if (clear(manager)) {
                hooks.info("Chrome 152 recently-closed history truly cleared at " + reason);
            }
        } catch (Throwable t) {
            hooks.error("Chrome 152 recently-closed clear at " + reason, t);
        }
    }

    private boolean clear(Object manager) throws ReflectiveOperationException {
        // 1) Remove recently closed window/session records.
        Object multiInstanceManager = Reflect.get(manager, "c");
        if (multiInstanceManager != null) {
            Object rawInstances = Reflect.call(multiInstanceManager, "k");
            ArrayList<Integer> ids = new ArrayList<>();
            if (rawInstances instanceof List<?>) {
                for (Object info : (List<?>) rawInstances) {
                    if (info == null) continue;
                    try {
                        ids.add(Reflect.getInt(info, "a"));
                    } catch (Throwable ignored) {}
                }
            }
            Reflect.call(multiInstanceManager, "f",
                    CLOSE_WINDOW_SOURCE_RECENT_TABS, ids);
        }

        // 2) Clear TabRestoreService entries through the exact Chrome 152 JNI selector.
        Object bridge = Reflect.get(manager, "d");
        if (bridge == null) return false;
        long nativePtr = Reflect.getLong(bridge, "a");
        if (nativePtr == 0L) return false;
        Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
        Method clearNative = Reflect.exact(nativeClass, "VJ", int.class, long.class);
        clearNative.invoke(null, NATIVE_CLEAR_RECENTLY_CLOSED, nativePtr);

        // 3) Match Chrome's inlined clear path and drop cached closed-window metadata.
        try {
            Object windowCache = Reflect.get(manager, "f");
            if (windowCache instanceof Map<?, ?>) ((Map<?, ?>) windowCache).clear();
        } catch (Throwable ignored) {}
        return true;
    }
}
