package com.yagay.chromex;

import java.util.concurrent.atomic.AtomicBoolean;

/** Loads the native Chrome download conflict policy hook inside the target browser process. */
final class NativeDownloadConflictBridge {
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private NativeDownloadConflictBridge() {}

    static void install(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || hooks == null || !ATTEMPTED.compareAndSet(false, true)) return;
        try {
            System.loadLibrary("chromex_native");
        } catch (Throwable t) {
            hooks.warn("native download conflict library load failed: "
                    + t.getClass().getSimpleName() + ':' + safe(t.getMessage()));
            return;
        }

        try {
            String detail = nativeInstall(runtime.versionName);
            if (detail != null && detail.startsWith("ACTIVE")) {
                hooks.info("native download conflict hook " + detail);
            } else {
                hooks.warn("native download conflict hook inactive: " + detail);
            }
        } catch (Throwable t) {
            hooks.error("native download conflict hook install", t);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static native String nativeInstall(String chromeVersion);
}
