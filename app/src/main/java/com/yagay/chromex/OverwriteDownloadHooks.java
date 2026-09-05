package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Replaces Chromium's Android duplicate-file "uniquify" path with an opt-in overwrite flow.
 *
 * The stable Java bridge receives the original desired file path as showDialog argument #1. When
 * overwrite is enabled we remove that exact existing target first, then accept the duplicate
 * callback. Chromium can therefore keep the original target name instead of reserving " (1)".
 *
 * Safety rules:
 * - Only absolute files under the primary shared-storage root are eligible.
 * - Directories are never deleted.
 * - MediaStore deletion is attempted first/alongside File.delete so metadata does not remain stale.
 * - If deletion cannot be verified, Chrome's original dialog is kept instead of silently uniquifying.
 */
final class OverwriteDownloadHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    OverwriteDownloadHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:download:overwrite-duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                        hooks.warn("same-name overwrite: duplicate bridge path unavailable");
                        return chain.proceed();
                    }

                    String path = (String) chain.getArg(1);
                    long callbackId = lastLong(chain.getArgs().toArray());
                    if (callbackId == 0L) {
                        hooks.warn("same-name overwrite: duplicate callback id unavailable");
                        return chain.proceed();
                    }

                    DeleteResult deletion = deleteExistingTarget(runtime.application, path);
                    if (!deletion.success) {
                        hooks.warn("same-name overwrite: old target not safely removable: "
                                + deletion.detail);
                        return chain.proceed();
                    }

                    if (!confirmDuplicate(chain.getThisObject(), callbackId)) {
                        hooks.warn("same-name overwrite: old target removed but callback unresolved; "
                                + "keeping Chrome dialog");
                        return chain.proceed();
                    }

                    hooks.info("same-name overwrite accepted: " + safeName(path)
                            + " via " + deletion.detail);
                    return null;
                });
    }

    private boolean confirmDuplicate(Object bridge, long callbackId) {
        // Exact Chrome 152.0.7977.75 mapping verified from split_chrome.apk.
        if (Chrome152.matches(runtime)) {
            try {
                long ptr = nativePtr(bridge);
                if (ptr == 0L) return false;
                Class<?> nativeClass = Reflect.cls(runtime.classLoader, Chrome145.NATIVE);
                Method callback = Reflect.exact(nativeClass, "VJJZ",
                        int.class, long.class, long.class, boolean.class);
                callback.invoke(null, Chrome152.DUPLICATE_ACCEPT, ptr, callbackId, true);
                return true;
            } catch (Throwable t) {
                hooks.warn("same-name overwrite: Chrome 152 callback failed: "
                        + t.getClass().getSimpleName());
                return false;
            }
        }

        // Future builds: prefer Chromium's semantic JNI wrapper if it survives production R8/JNI.
        try {
            Class<?> jni = Reflect.cls(runtime.classLoader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni");
            Object instance = Reflect.callStatic(jni, "get");
            if (instance == null) return false;
            long ptr = nativePtr(bridge);
            if (ptr == 0L) return false;
            for (String name : new String[]{"onConfirmed", "accepted"}) {
                try {
                    Reflect.call(instance, name, ptr, callbackId, Boolean.TRUE);
                    return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private DeleteResult deleteExistingTarget(Context context, String rawPath) {
        if (context == null || rawPath == null || rawPath.isBlank()) {
            return DeleteResult.fail("missing context/path");
        }
        try {
            if (rawPath.startsWith("content://")) {
                Uri uri = Uri.parse(rawPath);
                int rows = context.getContentResolver().delete(uri, null, null);
                return rows > 0 ? DeleteResult.ok("content-uri")
                        : DeleteResult.fail("content uri delete returned 0");
            }

            File target = new File(rawPath).getCanonicalFile();
            File external = Environment.getExternalStorageDirectory().getCanonicalFile();
            String root = external.getPath();
            String path = target.getPath();
            if (!path.startsWith(root + File.separator)) {
                return DeleteResult.fail("outside shared storage");
            }
            if (target.isDirectory()) return DeleteResult.fail("target is directory");

            boolean existed = target.exists();
            boolean mediaDeleted = deleteMediaStoreRow(context.getContentResolver(), path);
            if (!target.exists()) {
                return DeleteResult.ok(mediaDeleted ? "MediaStore" : (existed ? "removed" : "already absent"));
            }

            boolean fileDeleted = target.delete();
            if (fileDeleted) {
                // Best effort metadata cleanup after a direct filesystem removal.
                deleteMediaStoreRow(context.getContentResolver(), path);
            }
            if (!target.exists()) {
                return DeleteResult.ok(fileDeleted ? "filesystem" : "MediaStore");
            }
            return DeleteResult.fail("delete denied");
        } catch (Throwable t) {
            return DeleteResult.fail(t.getClass().getSimpleName());
        }
    }

    private boolean deleteMediaStoreRow(ContentResolver resolver, String absolutePath) {
        if (resolver == null || absolutePath == null) return false;
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            int rows = resolver.delete(files,
                    MediaStore.MediaColumns.DATA + "=?", new String[]{absolutePath});
            return rows > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            long value = Reflect.getLong(bridge, "a");
            if (value != 0L) return value;
        } catch (Throwable ignored) {}

        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (value == 0L) continue;
                    if (found != null) return 0L;
                    found = field;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try {
            return found.getLong(bridge);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long lastLong(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
        }
        return 0L;
    }

    private static String safeName(String path) {
        try {
            return new File(path).getName();
        } catch (Throwable ignored) {
            return "download";
        }
    }

    private static final class DeleteResult {
        final boolean success;
        final String detail;

        private DeleteResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }

        static DeleteResult ok(String detail) {
            return new DeleteResult(true, detail);
        }

        static DeleteResult fail(String detail) {
            return new DeleteResult(false, detail);
        }
    }
}
