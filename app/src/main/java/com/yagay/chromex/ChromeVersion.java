package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

/** Runtime Chrome version helper used to select verified per-release hook profiles. */
final class ChromeVersion {
    private static volatile String cached;

    private ChromeVersion() {}

    static String name() {
        String value = cached;
        if (value != null) return value;
        synchronized (ChromeVersion.class) {
            value = cached;
            if (value != null) return value;
            cached = value = resolve();
            return value;
        }
    }

    static int major() {
        String value = name();
        if (value == null) return -1;
        int dot = value.indexOf('.');
        String head = dot < 0 ? value : value.substring(0, dot);
        try {
            return Integer.parseInt(head);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean is145() {
        return major() == 145;
    }

    static boolean is152() {
        return major() == 152;
    }

    private static String resolve() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (!(app instanceof Application)) return "unknown";
            Context context = (Context) app;
            PackageInfo info = context.getPackageManager().getPackageInfo(Chrome145.PACKAGE, 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
