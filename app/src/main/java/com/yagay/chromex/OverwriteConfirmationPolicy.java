package com.yagay.chromex;

import android.content.SharedPreferences;

/**
 * Keeps Chromium's native duplicate-download confirmation when requested, while preserving
 * completion-stage overwrite and filename/history normalization after the user confirms.
 */
final class OverwriteConfirmationPolicy {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final ThreadLocal<Boolean> SUPPRESS_AUTO_CONFIRM = new ThreadLocal<>();

    private OverwriteConfirmationPolicy() {}

    static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS_AUTO_CONFIRM.get());
    }

    static void install(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, DUPLICATE_BRIDGE);
            if (Reflect.named(type, "showDialog").isEmpty()) return;
        } catch (Throwable ignored) {
            return;
        }

        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:overwrite:confirm-policy", chain -> {
                    boolean overwrite = Config.stored(prefs, Config.OVERWRITE_DUPLICATE);
                    boolean confirm = Config.stored(prefs, Config.OVERWRITE_CONFIRM_DUPLICATE);
                    if (!overwrite || !confirm) return chain.proceed();

                    SUPPRESS_AUTO_CONFIRM.set(Boolean.TRUE);
                    try {
                        hooks.info("same-name overwrite: preserving native duplicate confirmation");
                        return chain.proceed();
                    } finally {
                        SUPPRESS_AUTO_CONFIRM.remove();
                    }
                });
        hooks.info("same-name overwrite duplicate-confirmation policy installed");
    }
}
