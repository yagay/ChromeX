package com.yagay.chromex;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/** Loads the native Chrome download conflict policy hook inside the target browser process. */
final class NativeDownloadConflictBridge {
    private static final long[] RETRIES_MS = {0L, 250L, 750L, 1500L, 3000L, 6000L};
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NativeDownloadConflictBridge() {}

    static void install(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || hooks == null || !STARTED.compareAndSet(false, true)) return;
        if (!loadLibrary(hooks)) return;
        for (int i = 0; i < RETRIES_MS.length; i++) {
            final int attempt = i;
            long delay = RETRIES_MS[i];
            MAIN.postDelayed(() -> attemptInstall(runtime, hooks, attempt), delay);
        }
    }

    private static boolean loadLibrary(HookSupport hooks) {
        if (LOADED.get()) return true;
        try {
            System.loadLibrary("chromex_native");
            LOADED.set(true);
            return true;
        } catch (Throwable t) {
            hooks.warn("native download conflict library load failed: "
                    + t.getClass().getSimpleName() + ':' + safe(t.getMessage()));
            return false;
        }
    }

    private static void attemptInstall(ChromeRuntime runtime, HookSupport hooks, int attempt) {
        if (ACTIVE.get()) return;
        try {
            String detail = nativeInstall(runtime.versionName);
            if (detail != null && detail.startsWith("ACTIVE")) {
                if (ACTIVE.compareAndSet(false, true)) {
                    hooks.info("native download conflict hook " + detail
                            + " attempt=" + attempt);
                }
                return;
            }
            if (detail != null && detail.contains("libchrome.so not loaded")) {
                if (attempt == RETRIES_MS.length - 1) {
                    hooks.warn("native download conflict hook inactive after retries: " + detail);
                }
                return;
            }
            hooks.warn("native download conflict hook inactive: " + detail);
        } catch (Throwable t) {
            hooks.error("native download conflict hook install", t);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static native String nativeInstall(String chromeVersion);
}
