package com.yagay.chromex;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Small cross-UID client for resolver records stored by DiagnosticProvider. */
final class ResolverCacheClient {
    private ResolverCacheClient() {}

    static String get(ChromeRuntime runtime, String symbol) {
        if (runtime == null || symbol == null) return null;
        Cursor cursor = null;
        try {
            String key = key(runtime, symbol);
            Uri uri = Uri.withAppendedPath(DiagnosticProvider.CACHE_URI, key);
            cursor = runtime.application.getContentResolver().query(uri,
                    new String[]{DiagnosticProvider.COL_VALUE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    static void put(ChromeRuntime runtime, String symbol, String value) {
        if (runtime == null || symbol == null || value == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put(DiagnosticProvider.COL_KEY, key(runtime, symbol));
            values.put(DiagnosticProvider.COL_VALUE, value);
            runtime.application.getContentResolver().insert(DiagnosticProvider.CACHE_URI, values);
        } catch (Throwable ignored) {}
    }

    private static String key(ChromeRuntime runtime, String symbol) {
        return runtime.resolverCacheKey() + ":" + symbol;
    }
}
