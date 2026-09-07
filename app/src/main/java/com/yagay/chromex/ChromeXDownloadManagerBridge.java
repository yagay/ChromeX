package com.yagay.chromex;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Constructor;

/**
 * Replaces Chromium's Java -> Android DownloadManager enqueue bridge.
 *
 * <p>Chrome still owns the request creation and its UI, but ChromeX owns the actual enqueue. This
 * keeps URL/cookie/referrer/user-agent semantics while making destination naming deterministic.
 * The original DownloadManagerBridge enqueue body is not executed once this hook handles a request.</p>
 */
final class ChromeXDownloadManagerBridge {
    private static final String BRIDGE =
            "org.chromium.chrome.browser.download.DownloadManagerBridge";
    private static final String RESPONSE =
            "org.chromium.chrome.browser.download.DownloadManagerBridge$DownloadEnqueueResponse";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    ChromeXDownloadManagerBridge(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Reflect.cls(runtime.classLoader, BRIDGE);
        } catch (Throwable t) {
            hooks.warn("ChromeX download manager bridge unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, BRIDGE, "enqueueNewDownload",
                "chromex:download-manager:replace-enqueue", chain -> {
                    Object request = chain.getArgs().size() > 0 ? chain.getArg(0) : null;
                    Object callback = chain.getArgs().size() > 1 ? chain.getArg(1) : null;
                    if (request == null || callback == null) return chain.proceed();
                    if (!handle(request, callback)) return chain.proceed();
                    return null;
                });
        hooks.info("ChromeX replacement downloader installed at DownloadManagerBridge.enqueueNewDownload");
    }

    private boolean handle(Object request, Object callback) {
        final RequestValues values = read(request);
        if (!values.usable()) {
            hooks.warn("ChromeX replacement downloader skipped unresolved request");
            return false;
        }

        Thread worker = new Thread(() -> {
            Object response = newResponse();
            if (response == null) {
                hooks.warn("ChromeX replacement downloader response unavailable; falling back impossible after dispatch");
                return;
            }

            long id = -1L;
            int failure = 0;
            boolean success = false;
            long start = System.currentTimeMillis();
            String filePath = null;
            try {
                Uri uri = Uri.parse(values.url);
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("unsupported scheme");
                }

                DownloadManager.Request dm = new DownloadManager.Request(uri);
                if (values.mime != null) dm.setMimeType(values.mime);
                String title = values.fileName;
                if (title != null) {
                    dm.setTitle(title);
                    dm.setDescription(title);
                }
                addHeader(dm, "Cookie", values.cookie);
                addHeader(dm, "Referer", values.referrer);
                addHeader(dm, "User-Agent", values.userAgent);

                if (values.notifyCompleted) {
                    if (values.fileName == null) throw new IllegalStateException("filename missing");
                    File downloadDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    File target = new File(downloadDir, values.fileName).getCanonicalFile();
                    if (!sameDirectory(downloadDir, target)) {
                        throw new IllegalArgumentException("unsafe filename");
                    }
                    prepareExactTarget(target, values.fileName);
                    dm.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            values.fileName);
                    dm.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    filePath = target.getAbsolutePath();
                } else {
                    File dir = new File(runtime.application.getExternalFilesDir(null), "Download");
                    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                        throw new IllegalStateException("private download directory unavailable");
                    }
                    File target = new File(dir, values.fileName == null ? "download" : values.fileName)
                            .getCanonicalFile();
                    if (!sameDirectory(dir, target)) throw new IllegalArgumentException("unsafe filename");
                    if (target.exists() && !target.delete()) {
                        throw new IllegalStateException("cannot replace private target");
                    }
                    dm.setDestinationUri(Uri.fromFile(target));
                    dm.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
                    filePath = target.getAbsolutePath();
                }

                DownloadManager manager = (DownloadManager) runtime.application
                        .getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                id = manager.enqueue(dm);
                success = id >= 0L;
                if (!success) failure = DownloadManager.ERROR_UNKNOWN;
            } catch (IllegalArgumentException e) {
                failure = DownloadManager.ERROR_UNHANDLED_HTTP_CODE;
                hooks.warn("ChromeX replacement downloader rejected request: " + e.getMessage());
            } catch (Throwable t) {
                failure = DownloadManager.ERROR_FILE_ERROR;
                hooks.warn("ChromeX replacement downloader enqueue failed: "
                        + t.getClass().getSimpleName() + ":" + safe(t.getMessage()));
            }

            set(response, "result", success);
            set(response, "failureReason", failure);
            set(response, "downloadId", id);
            set(response, "startTime", start);
            set(response, "filePath", filePath);
            dispatchCallback(callback, response);

            hooks.info("ChromeX replacement download enqueue: " + values.fileName
                    + " id=" + id + " success=" + success
                    + " exact=" + values.notifyCompleted);
        }, "ChromeX-download-enqueue");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void prepareExactTarget(File target, String displayName) throws Exception {
        if (target == null) return;
        if (target.exists()) {
            if (target.isDirectory()) throw new IllegalStateException("target is directory");
            if (!target.delete()) {
                deleteMediaStoreRow(displayName);
            }
        } else {
            // Remove a stale MediaStore/DownloadManager row before enqueueing the exact same name.
            deleteMediaStoreRow(displayName);
        }
        if (target.exists()) {
            throw new IllegalStateException("existing target cannot be replaced");
        }
    }

    private void deleteMediaStoreRow(String displayName) {
        if (displayName == null || displayName.isBlank()) return;
        Cursor cursor = null;
        try {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.MediaColumns._ID};
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] args = {displayName, Environment.DIRECTORY_DOWNLOADS + "/"};
            cursor = runtime.application.getContentResolver().query(
                    collection, projection, selection, args, null);
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            while (cursor.moveToNext() && idColumn >= 0) {
                long row = cursor.getLong(idColumn);
                Uri item = Uri.withAppendedPath(collection, Long.toString(row));
                try { runtime.application.getContentResolver().delete(item, null, null); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }
    }

    private RequestValues read(Object request) {
        return new RequestValues(
                string(request, "url"),
                safeFileName(string(request, "fileName")),
                string(request, "mimeType"),
                string(request, "cookie"),
                string(request, "referrer"),
                string(request, "userAgent"),
                bool(request, "notifyCompleted", true));
    }

    private Object newResponse() {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, RESPONSE);
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable t) {
            hooks.warn("ChromeX replacement response creation failed: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private void dispatchCallback(Object callback, Object response) {
        runtime.application.getMainExecutor().execute(() -> {
            try {
                Reflect.call(callback, "onResult", response);
            } catch (Throwable first) {
                try { Reflect.call(callback, "onResult", (Object) response); }
                catch (Throwable second) {
                    hooks.warn("ChromeX replacement callback failed: "
                            + second.getClass().getSimpleName());
                }
            }
        });
    }

    private static void addHeader(DownloadManager.Request request, String name, String value) {
        if (request == null || value == null || value.isBlank()) return;
        try { request.addRequestHeader(name, value); } catch (Throwable ignored) {}
    }

    private static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = DownloadNamePolicy.fileNameOnly(raw);
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    private static boolean sameDirectory(File directory, File target) {
        try {
            File dir = directory.getCanonicalFile();
            File parent = target.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(dir);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String string(Object owner, String field) {
        try {
            Object value = Reflect.get(owner, field);
            return value instanceof String && !((String) value).isBlank() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean bool(Object owner, String field, boolean fallback) {
        try {
            Object value = Reflect.get(owner, field);
            return value instanceof Boolean ? (Boolean) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void set(Object owner, String field, Object value) {
        try { Reflect.set(owner, field, value); } catch (Throwable ignored) {}
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class RequestValues {
        final String url;
        final String fileName;
        final String mime;
        final String cookie;
        final String referrer;
        final String userAgent;
        final boolean notifyCompleted;

        RequestValues(String url, String fileName, String mime, String cookie,
                      String referrer, String userAgent, boolean notifyCompleted) {
            this.url = url;
            this.fileName = fileName;
            this.mime = mime;
            this.cookie = cookie;
            this.referrer = referrer;
            this.userAgent = userAgent;
            this.notifyCompleted = notifyCompleted;
        }

        boolean usable() { return url != null && (!notifyCompleted || fileName != null); }
    }
}
