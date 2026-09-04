package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DiagnosticExporter {
    private static final int COMMAND_LIMIT = 4 * 1024 * 1024;

    private DiagnosticExporter() {}

    static final class Result {
        final boolean success;
        final String message;
        final Uri uri;

        Result(boolean success, String message, Uri uri) {
            this.success = success;
            this.message = message;
            this.uri = uri;
        }
    }

    static Result export(Context context, SharedPreferences prefs) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "ChromeX-diagnostic-" + stamp + ".zip";
        Uri uri = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ChromeX");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");

            try (OutputStream raw = resolver.openOutputStream(uri, "w");
                 ZipOutputStream zip = new ZipOutputStream(raw)) {
                add(zip, "README.txt",
                        "ChromeX diagnostic package\n"
                                + "Generated: " + now() + "\n\n"
                                + "Recommended reproduction flow:\n"
                                + "1. Enable Diagnostic mode in ChromeX.\n"
                                + "2. Tap 'Re-scan hook points' or force-stop and reopen Chrome.\n"
                                + "3. Reproduce each broken ChromeX feature once.\n"
                                + "4. Return to ChromeX and export this package.\n\n"
                                + "The package contains only ChromeX settings/status, automatic hook-point probes,\n"
                                + "hook install/hit reports, filtered logcat, and filtered LSPosed log excerpts.\n");
                add(zip, "device_and_packages.txt", deviceInfo(context));
                add(zip, "settings.txt", settings(prefs));
                add(zip, "module_session.txt", prefString(prefs, Diagnostics.KEY_SESSION));
                add(zip, "hook_points.txt", prefString(prefs, Diagnostics.KEY_SCAN_REPORT));
                add(zip, "hook_install.txt", prefString(prefs, Diagnostics.KEY_HOOK_REPORT));
                add(zip, "hook_hits.txt", prefString(prefs, Diagnostics.KEY_HIT_REPORT));
                add(zip, "module_events.txt", prefString(prefs, Diagnostics.KEY_EVENT_REPORT));

                String logcat = commandWithFallback(
                        new String[]{"su", "-c", "logcat -d -v threadtime -t 6000"},
                        new String[]{"logcat", "-d", "-v", "threadtime", "-t", "1500"});
                add(zip, "logcat_filtered.txt", filterRelevant(logcat));

                String lspd = run(new String[]{"su", "-c",
                        "for d in /data/adb/lspd/log /data/adb/lsposed/log; do "
                                + "[ -d \"$d\" ] || continue; "
                                + "for f in \"$d\"/* \"$d\"/*/* \"$d\"/*/*/*; do "
                                + "[ -f \"$f\" ] || continue; "
                                + "echo ===== $f =====; tail -n 900 \"$f\" 2>/dev/null; "
                                + "done; done"});
                add(zip, "lsposed_filtered.txt", filterRelevant(lspd));
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new Result(true,
                    "已保存到 Download/ChromeX/" + name, uri);
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {}
            }
            return new Result(false,
                    "导出失败: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
        }
    }

    static boolean restartChrome(Context context) {
        boolean stopped = false;
        try {
            String output = run(new String[]{"su", "-c", "am force-stop " + Chrome145.PACKAGE});
            stopped = output != null;
            Thread.sleep(350L);
        } catch (Throwable ignored) {}
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(Chrome145.PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
                return stopped;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String deviceInfo(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("generated=").append(now()).append('\n');
        out.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("brand=").append(Build.BRAND).append(" product=").append(Build.PRODUCT).append('\n');
        out.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        out.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        appendPackage(context, out, "ChromeX", context.getPackageName());
        appendPackage(context, out, "Chrome", Chrome145.PACKAGE);
        return out.toString();
    }

    private static void appendPackage(Context context, StringBuilder out, String label, String pkg) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            out.append(label).append(" package=").append(pkg)
                    .append(" version=").append(info.versionName)
                    .append(" code=").append(info.getLongVersionCode()).append('\n');
        } catch (Throwable t) {
            out.append(label).append(" package=").append(pkg)
                    .append(" unavailable: ").append(t.getClass().getSimpleName()).append('\n');
        }
    }

    private static String settings(SharedPreferences prefs) {
        String[] keys = {
                Config.CLEAN_START, Config.NEWTAB_HOME, Config.CLEAR_CLOSED_TABS,
                Config.BYPASS_DANGEROUS, Config.BYPASS_INSECURE, Config.BYPASS_DUPLICATE,
                Config.BYPASS_POLICY, Config.BYPASS_LOCATION, Config.BYPASS_OPEN,
                Config.AUTO_INSTALL_APK, Config.APK_TOAST, Config.ALL_DOWNLOAD_TOAST,
                Config.HIDE_TRANSLATE, Config.DIAGNOSTIC_MODE
        };
        StringBuilder out = new StringBuilder();
        for (String key : keys) out.append(key).append('=').append(Config.get(prefs, key)).append('\n');
        if (prefs != null) {
            try {
                out.append("last_scan_ms=").append(prefs.getLong(Diagnostics.KEY_LAST_SCAN, 0L)).append('\n');
            } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    private static String prefString(SharedPreferences prefs, String key) {
        if (prefs == null) return "RemotePreferences unavailable.\n";
        try {
            String value = prefs.getString(key, "");
            return value == null || value.isEmpty() ? "No data recorded.\n" : value;
        } catch (Throwable t) {
            return "Read failed: " + t + "\n";
        }
    }

    private static String commandWithFallback(String[] preferred, String[] fallback) {
        String value = run(preferred);
        if (value == null || value.isBlank() || value.startsWith("COMMAND_ERROR:")) {
            value = run(fallback);
        }
        return value == null ? "" : value;
    }

    private static String run(String[] command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (total < COMMAND_LIMIT) {
                int n = input.read(buffer, 0, Math.min(buffer.length, COMMAND_LIMIT - total));
                if (n < 0) break;
                out.write(buffer, 0, n);
                total += n;
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "COMMAND_ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String filterRelevant(String raw) {
        if (raw == null || raw.isBlank()) return "No log data available. Root may be unavailable.\n";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.contains("chromex")
                        || low.contains("com.yagay.chromex")
                        || low.contains("com.android.chrome")
                        || low.contains("chromium")
                        || low.contains("lsposed")
                        || low.contains("libxposed")
                        || low.contains("xposed")
                        || low.contains("nosuchmethod")
                        || low.contains("classnotfound")
                        || low.contains("downloadcontroller")
                        || low.contains("downloadmanagerservice")
                        || low.contains("fatal exception")) {
                    out.append(line).append('\n');
                    if (out.length() >= 2_500_000) {
                        out.append("... filtered log truncated ...\n");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            return raw.length() > 2_500_000 ? raw.substring(0, 2_500_000) : raw;
        }
        if (out.length() == 0) {
            return "No matching ChromeX/Chrome/LSPosed lines found.\n\nCommand output header:\n"
                    + raw.substring(0, Math.min(raw.length(), 2000));
        }
        return out.toString();
    }

    private static void add(ZipOutputStream zip, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        zip.write(data);
        zip.closeEntry();
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }
}
