package com.yagay.chromex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Receives diagnostic data from the hooked Chrome process and stores it in ChromeX's own UID.
 * RemotePreferences remain read-only inside hooked applications; they are never used as a log sink.
 */
public final class DiagnosticProvider extends ContentProvider {
    static final String AUTHORITY = "com.yagay.chromex.diagnostics";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/write");
    static final String STORE_FILE = "chromex_diagnostics";

    static final String COL_KIND = "kind";
    static final String COL_TEXT = "text";
    static final String COL_TIME = "time";

    static final String KIND_SESSION = "session";
    static final String KIND_HOOK = "hook";
    static final String KIND_HITS = "hits";
    static final String KIND_EVENTS = "events";
    static final String KIND_SCAN = "scan";

    private static final int MAX_HOOK_CHARS = 120_000;
    private static final Object LOCK = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!callerAllowed()) {
            throw new SecurityException("ChromeX diagnostics accepts only Chrome or ChromeX callers");
        }
        if (values == null) return null;
        Context context = getContext();
        if (context == null) return null;

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
                    prefs.edit()
                            .clear()
                            .putString(Diagnostics.KEY_SESSION, text)
                            .putLong(Diagnostics.KEY_LAST_SCAN, 0L)
                            .apply();
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
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.chromex.diagnostic";
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
