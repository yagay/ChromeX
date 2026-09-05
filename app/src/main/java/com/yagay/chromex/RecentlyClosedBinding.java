package com.yagay.chromex;

import android.app.Activity;

import java.lang.reflect.Method;

/** Clears Chromium's real TabRestoreService through RecentlyClosedBridge before exact fallbacks. */
final class RecentlyClosedBinding {
    private static final String PROFILE_MANAGER =
            "org.chromium.chrome.browser.profiles.ProfileManager";
    private static final String TAB_MODEL_SELECTOR =
            "org.chromium.chrome.browser.tabmodel.TabModelSelector";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final Method clearMethod;

    RecentlyClosedBinding(ChromeRuntime runtime, HookSupport hooks, Method clearMethod) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.clearMethod = clearMethod;
    }

    boolean available() {
        return clearMethod != null;
    }

    boolean clear(Activity activity, String reason) {
        if (activity == null || clearMethod == null) return false;
        Object bridge = null;
        try {
            Object profile = regularProfile();
            Object selector = selector(activity);
            if (profile == null || selector == null) return false;
            bridge = Reflect.construct(clearMethod.getDeclaringClass(), profile, selector);
            clearMethod.invoke(bridge);
            hooks.info("recently-closed source cleared through RecentlyClosedBridge at " + reason);
            return true;
        } catch (Throwable t) {
            hooks.warn("RecentlyClosedBridge source unavailable at " + reason + ": "
                    + t.getClass().getSimpleName());
            return false;
        } finally {
            if (bridge != null) {
                try { Reflect.call(bridge, "destroy"); } catch (Throwable ignored) {}
            }
        }
    }

    private Object regularProfile() {
        try {
            Class<?> manager = Reflect.cls(runtime.classLoader, PROFILE_MANAGER);
            Object value = Reflect.callStatic(manager, "getLastUsedRegularProfile");
            if (value != null) return value;
        } catch (Throwable ignored) {}
        return null;
    }

    private Object selector(Activity activity) {
        try {
            Class<?> selectorType = Reflect.cls(runtime.classLoader, TAB_MODEL_SELECTOR);
            Object value = Reflect.findFieldValueByType(activity, selectorType);
            if (value != null) return value;
        } catch (Throwable ignored) {}
        return null;
    }
}
