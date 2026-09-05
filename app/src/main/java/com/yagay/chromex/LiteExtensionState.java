package com.yagay.chromex;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-extension enable state for the LITE compatibility backend. */
final class LiteExtensionState {
    private static final String PREFS = "chromex_lite_extension_state";

    private LiteExtensionState() {}

    static boolean isEnabled(Context context, String id) {
        if (context == null || id == null || id.isBlank()) return false;
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(id, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean setEnabled(Context context, String id, boolean enabled) {
        if (context == null || id == null || id.isBlank()) return false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.edit().putBoolean(id, enabled).commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void remove(Context context, String id) {
        if (context == null || id == null) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
        } catch (Throwable ignored) {}
    }
}
