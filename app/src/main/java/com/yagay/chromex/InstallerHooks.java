package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

final class InstallerHooks {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long WAIT_MS = 60_000L;
    private static final long POLL_MS = 500L;
    private static final long DEDUP_WINDOW_MS = 90_000L;

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChromeX-apk-installer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<InstallStamp> last = new AtomicReference<>();

    InstallerHooks(XposedModule module, HookSupport hooks,
                   SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        // Stable/current paths first.
        hookManagerServiceCompletion();
        hookControllerCompletion();
        hookCurrentOpenDownload();

        // Chrome 145 compatibility paths.
        hookNotificationCompletion();
        hookOfflineCompletion();
        hookLegacyOpenDownload();
    }

    private void hookManagerServiceCompletion() {
        hooks.all(loader, Chrome145.DOWNLOAD_MANAGER_SERVICE, "onDownloadCompleted",
                "chromex:installer:manager-complete", chain -> {
                    Object result = chain.proceed();
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return result;
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    if (info != null) enqueueDownloadInfo(info, "DownloadManagerService");
                    return result;
                });
    }

    private void hookControllerCompletion() {
        // Signature has changed across Chrome releases. Hook the stable method name and locate the
        // DownloadInfo argument by type instead of assuming (Tab, DownloadInfo, boolean).
        hooks.all(loader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:installer:controller", chain -> {
                    Object result = chain.proceed();
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return result;
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    if (info != null) enqueueDownloadInfo(info, "DownloadController");
                    return result;
                });
    }

    private void hookCurrentOpenDownload() {
        // Current Chromium keeps this JNI-facing entry point stable. It receives filePath, mime,
        // guid, profile, originalUrl, referrer, source, fileName.
        hooks.all(loader, Chrome145.DOWNLOAD_UTILS, "openDownload",
                "chromex:installer:open-current", chain -> {
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (args.length < 2) return chain.proceed();
                    String path = args[0] instanceof String ? (String) args[0] : null;
                    String mime = args[1] instanceof String ? (String) args[1] : null;
                    String name = args.length > 0 && args[args.length - 1] instanceof String
                            ? (String) args[args.length - 1] : null;
                    if (!isApk(mime, name != null ? name : path)) return chain.proceed();
                    try {
                        String fileName = normalizedName(name, path);
                        Uri uri = resolveUri(path, fileName);
                        if (uri != null && launch(uri, fileName == null ? uri.toString() : fileName)) {
                            return null;
                        }
                    } catch (Throwable t) {
                        hooks.error("current open-download APK hook", t);
                    }
                    return chain.proceed();
                });
    }

    private void hookNotificationCompletion() {
        hooks.exact(loader, Chrome145.DOWNLOAD_EVENT_RUNNABLE, "run", new Class<?>[0],
                "chromex:installer:event:145", chain -> {
                    Object result = chain.proceed();
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return result;
                    try {
                        Object event = Reflect.get(chain.getThisObject(), "P");
                        if (event == null || Reflect.getInt(event, "a") != 1) return result;
                        Object info = Reflect.get(event, "b");
                        if (info == null) return result;
                        String mime = stringField(info, "c");
                        String path = stringField(info, "e");
                        String name = stringField(info, "g");
                        enqueueIfApk(mime, path, name);
                    } catch (Throwable t) {
                        hooks.warn("legacy download-event path unavailable: "
                                + t.getClass().getSimpleName());
                    }
                    return result;
                });
    }

    private void hookOfflineCompletion() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            Class<?> visuals = Reflect.cls(loader, Chrome145.OFFLINE_VISUALS);
            hooks.exact(loader, Chrome145.OFFLINE_COMPLETE, "f",
                    new Class<?>[]{item, visuals}, "chromex:installer:offline:145", chain -> {
                        Object result = chain.proceed();
                        if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return result;
                        try {
                            Object value = chain.getArg(0);
                            if (value == null) return result;
                            int state = Reflect.getInt(value, "m0");
                            if (state != 1 && state != 2) return result;
                            enqueueIfApk(stringField(value, "f0"),
                                    stringField(value, "P"), stringField(value, "e0"));
                        } catch (Throwable t) {
                            hooks.warn("legacy offline-item path unavailable: "
                                    + t.getClass().getSimpleName());
                        }
                        return result;
                    });
        } catch (Throwable t) {
            hooks.warn("legacy offline APK hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void hookLegacyOpenDownload() {
        try {
            Class<?> request = Reflect.cls(loader, Chrome145.OPEN_DOWNLOAD_REQUEST);
            hooks.exact(loader, Chrome145.DOWNLOAD_UTILS, "a", new Class<?>[]{request},
                    "chromex:installer:open:145", chain -> {
                        if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return chain.proceed();
                        Object value = chain.getArg(0);
                        if (value == null) return chain.proceed();
                        try {
                            String mime = stringField(value, "b");
                            String name = stringField(value, "a");
                            if (!isApk(mime, name)) return chain.proceed();
                            String raw = stringField(value, "i");
                            Uri uri = resolveUri(raw, name);
                            if (uri == null) return chain.proceed();
                            if (!launch(uri, name == null ? uri.toString() : name)) return chain.proceed();
                            return Boolean.TRUE;
                        } catch (Throwable t) {
                            hooks.warn("legacy open-download path unavailable: "
                                    + t.getClass().getSimpleName());
                            return chain.proceed();
                        }
                    });
        } catch (Throwable t) {
            hooks.warn("legacy open-download APK hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> infoType = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && infoType.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void enqueueDownloadInfo(Object info, String source) {
        try {
            String mime = stringAccessor(info, "getMimeType", "c");
            String path = stringAccessor(info, "getFilePath", "e");
            String name = stringAccessor(info, "getFileName", "g");
            hooks.info(source + " completion: " + (name == null ? "<unnamed>" : name));
            enqueueIfApk(mime, path, name);
        } catch (Throwable t) {
            hooks.error(source + " DownloadInfo", t);
        }
    }

    private void enqueueIfApk(String mime, String path, String name) {
        if (!isApk(mime, name != null ? name : path)) return;
        String fileName = normalizedName(name, path);
        if (fileName == null) return;
        worker.execute(() -> waitForAndInstall(path, fileName));
    }

    private void waitForAndInstall(String raw, String fileName) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return;
            try {
                Uri uri = resolveUri(raw, fileName);
                if (uri != null && launch(uri, fileName)) return;
            } catch (Throwable t) {
                hooks.error("APK polling", t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        hooks.warn("APK not resolvable after 60s: " + fileName);
    }

    private Uri resolveUri(String raw, String fileName) {
        if (raw != null && raw.startsWith("content://")) return Uri.parse(raw);

        File file = null;
        if (raw != null && raw.startsWith("/")) {
            File candidate = new File(raw);
            if (candidate.exists() && candidate.length() > 0) file = candidate;
        }
        if (file == null && fileName != null) {
            try {
                File candidate = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName);
                if (candidate.exists() && candidate.length() > 0) file = candidate;
            } catch (Throwable ignored) {}
        }
        if (file == null && fileName != null) {
            try {
                Class<?> pathUtils = Reflect.cls(loader, "org.chromium.base.PathUtils");
                Method m = Reflect.exact(pathUtils, "getDownloadsDirectory");
                Object dir = m.invoke(null);
                if (dir instanceof String) {
                    File candidate = new File((String) dir, fileName);
                    if (candidate.exists() && candidate.length() > 0) file = candidate;
                }
            } catch (Throwable ignored) {}
        }

        if (file != null) {
            Uri fromChrome = chromeContentUri(file.getAbsolutePath());
            if (fromChrome != null) return fromChrome;
        }
        return fileName == null ? null : mediaStoreUri(fileName);
    }

    private Uri chromeContentUri(String absolutePath) {
        // Current Chromium utility names can change. Search compatible one-String methods that
        // return Uri first, then keep the old Chrome 145 'e' method as fallback.
        try {
            Class<?> utils = Reflect.cls(loader, Chrome145.DOWNLOAD_UTILS);
            for (Method method : utils.getDeclaredMethods()) {
                if (method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != String.class
                        || !Uri.class.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                Object value = method.invoke(null, absolutePath);
                if (value instanceof Uri) {
                    Uri uri = (Uri) value;
                    if (uri.getScheme() != null && !"file".equals(uri.getScheme())) return uri;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> utils = Reflect.cls(loader, Chrome145.DOWNLOAD_UTILS);
            Method method = Reflect.exact(utils, "e", String.class);
            Object value = method.invoke(null, absolutePath);
            if (value instanceof Uri) {
                Uri uri = (Uri) value;
                if (uri.getScheme() != null && !"file".equals(uri.getScheme())) return uri;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Uri mediaStoreUri(String fileName) {
        Context context = chromeContext();
        if (context == null) return null;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Files.getContentUri("external");
            cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{fileName},
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

    private boolean launch(Uri uri, String key) {
        long now = System.currentTimeMillis();
        InstallStamp previous = last.get();
        if (previous != null && previous.key.equals(key) && now - previous.time < DEDUP_WINDOW_MS) {
            return true;
        }
        Context context = chromeContext();
        if (context == null) return false;
        InstallStamp next = new InstallStamp(key, now);
        if (!last.compareAndSet(previous, next)) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            hooks.info("APK installer launched: " + key);
            return true;
        } catch (Throwable t) {
            last.compareAndSet(next, previous);
            hooks.error("launch APK installer", t);
            return false;
        }
    }

    private Context chromeContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringAccessor(Object value, String getter, String legacyField) {
        if (value == null) return null;
        try {
            Object result = Reflect.call(value, getter);
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return stringField(value, legacyField);
    }

    private static String stringField(Object value, String field) {
        try {
            Object result = Reflect.get(value, field);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizedName(String name, String path) {
        String value = name;
        if (value == null || value.isBlank()) value = path;
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("content://")) return value;
        try {
            return new File(value).getName();
        } catch (Throwable ignored) {
            return value;
        }
    }

    static boolean isApk(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("package-archive")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static final class InstallStamp {
        final String key;
        final long time;

        InstallStamp(String key, long time) {
            this.key = key;
            this.time = time;
        }
    }
}
