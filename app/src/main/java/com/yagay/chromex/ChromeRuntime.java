package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable runtime state after a Chromium browser's real classloader is ready. */
final class ChromeRuntime {
    final Application application;
    final ApplicationInfo applicationInfo;
    final ClassLoader classLoader;
    final String packageName;
    final String versionName;
    final long versionCode;
    final int majorVersion;
    /** Preferred code path kept for compatibility with older resolver code. */
    final String chromeSplitPath;
    private final List<String> codePaths;

    ChromeRuntime(Application application, ApplicationInfo applicationInfo,
                  ClassLoader classLoader, String preferredCodePath) {
        this.application = application;
        this.applicationInfo = applicationInfo;
        this.classLoader = classLoader;
        this.packageName = application == null ? "unknown" : application.getPackageName();
        this.codePaths = buildCodePaths(applicationInfo, preferredCodePath);
        this.chromeSplitPath = codePaths.isEmpty() ? null : codePaths.get(0);
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

    /** Every APK/split that may contain Chromium dex, preferred loader source first. */
    List<String> dexPaths() {
        return codePaths;
    }

    String primaryDexPath() {
        return codePaths.isEmpty() ? null : codePaths.get(0);
    }

    String resolverCacheKey() {
        StringBuilder out = new StringBuilder()
                .append(packageName).append(':')
                .append(versionCode).append(':')
                .append(versionName);
        for (String path : codePaths) {
            long length = -1L;
            long modified = -1L;
            try {
                File file = new File(path);
                length = file.length();
                modified = file.lastModified();
            } catch (Throwable ignored) {}
            out.append(':').append(new File(path).getName())
                    .append('@').append(length).append('@').append(modified);
        }
        return out.toString();
    }

    private static List<String> buildCodePaths(ApplicationInfo info, String preferred) {
        Set<String> unique = new LinkedHashSet<>();
        addPath(unique, preferred);
        if (info != null) {
            addPath(unique, info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (String path : info.splitSourceDirs) addPath(unique, path);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static void addPath(Set<String> out, String path) {
        if (path == null || path.isBlank()) return;
        try {
            out.add(new File(path).getCanonicalPath());
        } catch (Throwable ignored) {
            out.add(path);
        }
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
