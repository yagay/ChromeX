package com.yagay.chromex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Cross-UID transport owned by ChromeX. It receives diagnostics from Chrome and also stores small
 * resolver-cache records so future Chrome builds only need a DexKit scan once per installed build.
 */
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
    private static final Object LOCK = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE);
    }

    static void clearStore(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            SharedPreferences prefs = store(context);
            SharedPreferences.Editor editor = prefs.edit();
            // Keep resolver cache across diagnostic rescans; only diagnostic keys are reset.
            editor.remove(Diagnostics.KEY_SESSION)
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
            throw new SecurityException("ChromeX provider accepts only Chrome or ChromeX callers");
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
                    prefs.edit().putString(Diagnostics.KEY_EVENT_REPORT, text).apply();
                    break;
                case KIND_SCAN:
                    prefs.edit()
                            .putString(Diagnostics.KEY_SCAN_REPORT, text)
                            .putLong(Diagnostics.KEY_LAST_SCAN, time)
                            .apply();
                    break;
                default:
                    return null;
            }
        }
        return Uri.withAppendedPath(URI, Long.toString(time));
    }

    private Uri putCache(Context context, ContentValues values) {
        String key = values.getAsString(COL_KEY);
        String value = values.getAsString(COL_VALUE);
        if (key == null || key.isBlank() || value == null) return null;
        synchronized (LOCK) {
            store(context).edit().putString(CACHE_PREFIX + key, value).commit();
        }
        return Uri.withAppendedPath(CACHE_URI, key);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!callerAllowed()) {
            throw new SecurityException("ChromeX provider accepts only Chrome or ChromeX callers");
        }
        if (!isCacheUri(uri)) return null;
        Context context = getContext();
        if (context == null) return null;
        String key = uri == null ? null : uri.getLastPathSegment();
        if (key == null || "cache".equals(key)) return null;
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
        if (next.length() > maxChars) {
            next = "... older diagnostic lines trimmed ...\n"
                    + next.substring(next.length() - maxChars);
        }
        prefs.edit().putString(key, next).apply();
    }

    private boolean callerAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (Chrome145.PACKAGE.equals(pkg)) return true;
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return isCacheUri(uri)
                ? "vnd.android.cursor.item/vnd.chromex.resolver"
                : "vnd.android.cursor.item/vnd.chromex.diagnostic";
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
