package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves downloaded files to grantable content:// URIs. Never returns file://. */
final class InstallerUriResolver {
    static final class Result {
        final Uri uri;
        final boolean terminal;
        final String detail;

        private Result(Uri uri, boolean terminal, String detail) {
            this.uri = uri;
            this.terminal = terminal;
            this.detail = detail;
        }

        static Result ok(Uri uri, String detail) {
            return new Result(uri, true, detail);
        }

        static Result retry(String detail) {
            return new Result(null, false, detail);
        }

        static Result fail(String detail) {
            return new Result(null, true, detail);
        }
    }

    private InstallerUriResolver() {}

    static Result resolve(Context context, ClassLoader loader, String raw, String fileName) {
        if (context == null) return Result.retry("Chrome context unavailable");
        if (raw != null && raw.startsWith("content://")) {
            Uri uri = Uri.parse(raw);
            return isContent(uri) ? Result.ok(uri, "existing content URI")
                    : Result.fail("invalid content URI");
        }

        File file = existingFile(raw);
        if (file == null && fileName != null && !fileName.isBlank()) {
            file = existingFile(new File(context.getExternalFilesDir(null),
                    "Download/" + fileName).getAbsolutePath());
        }

        if (file != null) {
            Uri uri = fromAndroidXFileProvider(context, loader, file);
            if (isContent(uri)) return Result.ok(uri, "Chrome FileProvider");

            uri = fromDownloadFileProvider(loader, file);
            if (isContent(uri)) return Result.ok(uri, "Chrome DownloadFileProvider");

            uri = fromDownloadUtils(loader, file.getAbsolutePath());
            if (isContent(uri)) return Result.ok(uri, "Chrome DownloadUtils");

            uri = mediaStoreUri(context, file.getAbsolutePath(), file.getName());
            if (isContent(uri)) return Result.ok(uri, "MediaStore");

            // The file already exists. Retrying cannot turn a permanently unshareable file:// URI
            // into content://, so fail once instead of logging every 500 ms for a minute.
            return Result.fail("completed file exists but no grantable content URI was resolved");
        }

        Uri media = mediaStoreUri(context, raw, fileName);
        if (isContent(media)) return Result.ok(media, "MediaStore by name");
        return Result.retry("download file not visible yet");
    }

    static boolean isContent(Uri uri) {
        return uri != null && "content".equalsIgnoreCase(uri.getScheme());
    }

    private static File existingFile(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith("/")) return null;
        try {
            File file = new File(raw).getCanonicalFile();
            return file.exists() && file.isFile() && file.length() > 0 ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Uri fromAndroidXFileProvider(Context context, ClassLoader loader, File file) {
        for (String className : new String[]{
                "androidx.core.content.FileProvider",
                "android.support.v4.content.FileProvider"}) {
            try {
                Class<?> provider = Reflect.cls(loader, className);
                Method method = Reflect.exact(provider, "getUriForFile",
                        Context.class, String.class, File.class);
                Object value = method.invoke(null, context,
                        Chrome145.PACKAGE + ".FileProvider", file);
                if (value instanceof Uri && isContent((Uri) value)) return (Uri) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Uri fromDownloadFileProvider(ClassLoader loader, File file) {
        try {
            Class<?> provider = Reflect.cls(loader,
                    "org.chromium.chrome.browser.download.DownloadFileProvider");
            for (Method method : provider.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || !Uri.class.isAssignableFrom(method.getReturnType())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                method.setAccessible(true);
                Object value = method.invoke(null, file.getAbsolutePath());
                if (value instanceof Uri && isContent((Uri) value)) return (Uri) value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Uri fromDownloadUtils(ClassLoader loader, String path) {
        try {
            Class<?> utils = Reflect.cls(loader, Chrome145.DOWNLOAD_UTILS);
            for (Method method : utils.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != String.class
                        || !Uri.class.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                Object value = method.invoke(null, path);
                if (value instanceof Uri && isContent((Uri) value)) return (Uri) value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Uri mediaStoreUri(Context context, String absolutePath, String fileName) {
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Files.getContentUri("external");
            String selection;
            String[] args;
            if (absolutePath != null && absolutePath.startsWith("/")) {
                selection = MediaStore.MediaColumns.DATA + "=?";
                args = new String[]{absolutePath};
            } else if (fileName != null && !fileName.isBlank()) {
                selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                args = new String[]{fileName};
            } else {
                return null;
            }
            cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID}, selection, args,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }
}
