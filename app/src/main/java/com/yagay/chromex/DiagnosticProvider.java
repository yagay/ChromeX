package com.yagay.chromex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.List;

/** Cross-UID diagnostics and resolver-cache transport owned by ChromeX. */
public final class DiagnosticProvider extends ContentProvider {
    static final String AUTHORITY = "com.yagay.chromex.diagnostics";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/write");
    static final Uri CACHE_URI = Uri.parse("content://" + AUTHORITY + "/cache");
    static final String STORE_FILE = "chromex_diagnostics";

    static final String COL_KIND = "kind";
    static final String COL_TEXT = "text";
    static final String COL_TIME = "time";
    static final String COL_KEY = "key";
    static final String COL_VALUE = "value";

    static final String KIND_SESSION = "session";
    static final String KIND_HOOK = "hook";
    static final String KIND_HITS = "hits";
    static final String KIND_EVENTS = "events";
    static final String KIND_SCAN = "scan";

    private static final String CACHE_PREFIX = "resolver:";
    private static final int MAX_SESSION_CHARS = 24_000;
    private static final int MAX_HOOK_CHARS = 120_000;
    private static final int MAX_EVENT_CHARS = 80_000;
    private static final Object LOCK = new Object();

    @Override
    public boolean onCreate() { return true; }

    static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE);
    }

    static void clearStore(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            store(context).edit()
                    .remove(Diagnostics.KEY_SESSION)
                    .remove(Diagnostics.KEY_SCAN_REPORT)
                    .remove(Diagnostics.KEY_HOOK_REPORT)
                    .remove(Diagnostics.KEY_HIT_REPORT)
                    .remove(Diagnostics.KEY_EVENT_REPORT)
                    .remove(Diagnostics.KEY_LAST_SCAN)
                    .commit();
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!callerAllowed()) {
            throw new SecurityException("ChromeX provider accepts only selected/browser callers");
        }
        if (values == null) return null;
        Context context = getContext();
        if (context == null) return null;
        if (isCacheUri(uri)) return putCache(context, values);

        String kind = values.getAsString(COL_KIND);
        String text = values.getAsString(COL_TEXT);
        Long when = values.getAsLong(COL_TIME);
        long time = when == null ? System.currentTimeMillis() : when;
        if (kind == null) return null;
        if (text == null) text = "";

        SharedPreferences prefs = store(context);
        synchronized (LOCK) {
            switch (kind) {
                case KIND_SESSION:
                    append(prefs, Diagnostics.KEY_SESSION, text, MAX_SESSION_CHARS);
                    break;
                case KIND_HOOK:
                    append(prefs, Diagnostics.KEY_HOOK_REPORT, text, MAX_HOOK_CHARS);
                    break;
                case KIND_HITS:
                    prefs.edit().putString(Diagnostics.KEY_HIT_REPORT, text).apply();
                    break;
                case KIND_EVENTS:
                    if (isDeepScanEvent(text)) {
                        append(prefs, Diagnostics.KEY_EVENT_REPORT,
                                "[deep-scan]\n" + text, MAX_EVENT_CHARS);
                    } else {
                        prefs.edit().putString(Diagnostics.KEY_EVENT_REPORT,
                                trimTail(text, MAX_EVENT_CHARS)).apply();
                    }
                    break;
                case KIND_SCAN:
                    prefs.edit()
                            .putString(Diagnostics.KEY_SCAN_REPORT, text)
                            .putLong(Diagnostics.KEY_LAST_SCAN, time)
                            .apply();
                    try {
                        context.getSharedPreferences(Config.FILE, Context.MODE_PRIVATE)
                                .edit().putBoolean(Config.DIAGNOSTIC_MODE, false).apply();
                    } catch (Throwable ignored) {}
                    break;
                default:
                    return null;
            }
        }
        return Uri.withAppendedPath(URI, Long.toString(time));
    }

    private static boolean isDeepScanEvent(String text) {
        if (text == null) return false;
        return text.contains(" SCAN completed, chars=")
                || text.contains(" SCAN fatal:");
    }

    private Uri putCache(Context context, ContentValues values) {
        String key = values.getAsString(COL_KEY);
        String value = values.getAsString(COL_VALUE);
        if (key == null || key.isBlank() || value == null || !cacheKeyAllowedForCaller(key)) {
            return null;
        }
        synchronized (LOCK) {
            store(context).edit().putString(CACHE_PREFIX + key, value).commit();
        }
        return Uri.withAppendedPath(CACHE_URI, key);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!callerAllowed()) {
            throw new SecurityException("ChromeX provider accepts only selected/browser callers");
        }
        if (!isCacheUri(uri)) return null;
        Context context = getContext();
        if (context == null) return null;
        String key = uri == null ? null : uri.getLastPathSegment();
        if (key == null || "cache".equals(key) || !cacheKeyAllowedForCaller(key)) return null;
        String value = store(context).getString(CACHE_PREFIX + key, null);
        MatrixCursor cursor = new MatrixCursor(new String[]{COL_KEY, COL_VALUE}, 1);
        if (value != null) cursor.addRow(new Object[]{key, value});
        return cursor;
    }

    private static boolean isCacheUri(Uri uri) {
        return uri != null && !uri.getPathSegments().isEmpty()
                && "cache".equals(uri.getPathSegments().get(0));
    }

    private static void append(SharedPreferences prefs, String key, String line, int maxChars) {
        String old = prefs.getString(key, "");
        if (old == null) old = "";
        String next = old + line + (line.endsWith("\n") ? "" : "\n");
        prefs.edit().putString(key, trimTail(next, maxChars)).apply();
    }

    private static String trimTail(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return "... older diagnostic lines trimmed ...\n"
                + value.substring(value.length() - maxChars);
    }

    /**
     * Authorize by semantic browser role and explicit ChromeX selection rather than one package.
     * Cache records are additionally package-bound by cacheKeyAllowedForCaller().
     */
    private boolean callerAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return false;
        SharedPreferences modulePrefs = context.getSharedPreferences(Config.FILE, Context.MODE_PRIVATE);
        for (String pkg : packages) {
            if (pkg == null) continue;
            if (ChromiumTargets.isKnownPackage(pkg)
                    || Config.isDynamicTarget(modulePrefs, pkg)
                    || handlesHttps(context, pkg)) {
                return true;
            }
        }
        return false;
    }

    private boolean cacheKeyAllowedForCaller(String key) {
        if (key == null || key.isBlank()) return false;
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (pkg != null && key.startsWith(pkg + ":")) return true;
        }
        return false;
    }

    private static boolean handlesHttps(Context context, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setPackage(packageName);
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
            return handlers != null && !handlers.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String getType(Uri uri) {
        return isCacheUri(uri)
                ? "vnd.android.cursor.item/vnd.chromex.resolver"
                : "vnd.android.cursor.item/vnd.chromex.diagnostic";
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
