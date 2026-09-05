package com.yagay.chromex;

import android.app.Activity;

import java.lang.reflect.Method;

/** Exact-build fallback used only when TabClosureParams cannot suppress restore history. */
final class ChromiumHistoryFallback {
    private final ChromiumProfile profile;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final Chrome152HistoryCleaner chrome152;

    ChromiumHistoryFallback(ChromiumProfile profile, ClassLoader loader, HookSupport hooks) {
        this.profile = profile;
        this.loader = loader;
        this.hooks = hooks;
        this.chrome152 = profile != null && profile.is152()
                ? new Chrome152HistoryCleaner(loader, hooks) : null;
    }

    boolean clear(Activity activity, String reason) {
        if (profile == null) return false;
        if (profile.is152()) {
            return chrome152 != null && chrome152.clear(activity, reason);
        }
        return clear145(reason);
    }

    private boolean clear145(String reason) {
        try {
            Class<?> pm = Reflect.cls(loader, Chrome145.PROFILE_MANAGER);
            Object chromeProfile;
            try {
                chromeProfile = Reflect.callStatic(pm, "getLastUsedRegularProfile");
            } catch (Throwable ignored) {
                chromeProfile = Reflect.exact(pm, "b").invoke(null);
            }
            if (chromeProfile == null) return false;

            Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
            Method method = Reflect.exact(nativeClass, "VIOOOOOOO",
                    int.class, int.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class);
            method.invoke(null, 0, 4, chromeProfile, null,
                    new int[]{8}, new String[0], new int[0], new String[0], new int[0]);
            hooks.info("Chrome 145 recently-closed fallback cleared at " + reason);
            return true;
        } catch (Throwable t) {
            hooks.warn("Chrome 145 recently-closed fallback unavailable at " + reason + ": "
                    + t.getClass().getSimpleName());
            return false;
        }
    }
}
