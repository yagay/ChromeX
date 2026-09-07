package com.yagay.chromex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Narrow root bridge used only by supported Chrome packages to free one public-Download target.
 * Root work must run in ChromeX's own process because KernelSU's su/mount visibility is not
 * inherited by the hooked Chrome process.
 */
public final class RootBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "com.yagay.chromex.rootbridge";
    public static final String METHOD_DELETE_DOWNLOAD = "deleteDownloadTarget";

    private static final Set<String> ALLOWED_CALLERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
    ));

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su"
    };

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!METHOD_DELETE_DOWNLOAD.equals(method)) {
            out.putBoolean("success", false);
            out.putString("reason", "unsupported-method");
            return out;
        }
        if (!callerAllowed()) {
            out.putBoolean("success", false);
            out.putString("reason", "caller-not-allowed");
            return out;
        }

        String name = safeName(arg);
        if (name == null) {
            out.putBoolean("success", false);
            out.putString("reason", "invalid-name");
            return out;
        }

        try {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getCanonicalFile();
            File target = new File(dir, name).getCanonicalFile();
            if (target.getParentFile() == null || !dir.equals(target.getParentFile())) {
                out.putBoolean("success", false);
                out.putString("reason", "path-escape");
                return out;
            }

            String su = resolveSu();
            if (su == null) {
                out.putBoolean("success", false);
                out.putString("reason", "su-not-found");
                out.putString("target", target.getAbsolutePath());
                return out;
            }

            Process process = new ProcessBuilder(su, "-c",
                    "rm -f -- " + shellQuote(target.getAbsolutePath()))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.putBoolean("success", false);
                out.putString("reason", "timeout");
                out.putString("su", su);
                out.putString("target", target.getAbsolutePath());
                return out;
            }

            int exit = process.exitValue();
            boolean gone = !target.exists();
            out.putBoolean("success", exit == 0 && gone);
            out.putInt("exit", exit);
            out.putBoolean("gone", gone);
            out.putString("su", su);
            out.putString("target", target.getAbsolutePath());
            out.putString("reason", exit == 0 && gone ? "ok" : "delete-failed");
            return out;
        } catch (Throwable t) {
            out.putBoolean("success", false);
            out.putString("reason", t.getClass().getSimpleName() + ":" +
                    (t.getMessage() == null ? "" : t.getMessage()));
            return out;
        }
    }

    private boolean callerAllowed() {
        if (getContext() == null) return false;
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return true;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (ALLOWED_CALLERS.contains(pkg)) return true;
        }
        return false;
    }

    private static String resolveSu() {
        for (String candidate : SU_CANDIDATES) {
            try {
                File file = new File(candidate);
                if (file.isFile() && file.canExecute()) return candidate;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = DownloadNamePolicy.fileNameOnly(raw);
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
