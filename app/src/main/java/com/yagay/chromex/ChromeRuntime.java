package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.io.File;

/** Immutable runtime state after a Chromium browser's main classloader is actually ready. */
final class ChromeRuntime {
    final Application application;
    final ApplicationInfo applicationInfo;
    final ClassLoader classLoader;
    final String packageName;
    final String versionName;
    final long versionCode;
    final int majorVersion;
    final String chromeSplitPath;

    ChromeRuntime(Application application, ApplicationInfo applicationInfo,
                  ClassLoader classLoader, String chromeSplitPath) {
        this.application = application;
        this.applicationInfo = applicationInfo;
        this.classLoader = classLoader;
        this.packageName = application == null ? "unknown" : application.getPackageName();
        this.chromeSplitPath = chromeSplitPath;
        PackageInfo info = resolvePackageInfo(application, packageName);
        this.versionName = info == null || info.versionName == null ? "unknown" : info.versionName;
        this.versionCode = info == null ? -1L : info.getLongVersionCode();
        this.majorVersion = parseMajor(versionName);
    }

    boolean is145() {
        return majorVersion == 145;
    }

    boolean is152() {
        return majorVersion == 152;
    }

    String resolverCacheKey() {
        long length = -1L;
        long modified = -1L;
        try {
            if (chromeSplitPath != null) {
                File file = new File(chromeSplitPath);
                length = file.length();
                modified = file.lastModified();
            }
        } catch (Throwable ignored) {}
        return packageName + ":" + versionCode + ":" + versionName + ":" + length + ":" + modified;
    }

    private static PackageInfo resolvePackageInfo(Context context, String packageName) {
        if (context == null || packageName == null) return null;
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int parseMajor(String value) {
        if (value == null) return -1;
        int dot = value.indexOf('.');
        String head = dot < 0 ? value : value.substring(0, dot);
        try {
            return Integer.parseInt(head);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
