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
        hookNotificationCompletion();
        hookOfflineCompletion();
        hookOpenDownload();
        hookControllerCompletion();
    }

    private void hookNotificationCompletion() {
        hooks.exact(loader, Chrome145.DOWNLOAD_EVENT_RUNNABLE, "run", new Class<?>[0],
                "chromex:installer:event", chain -> {
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
                        hooks.error("download-event APK detection", t);
                    }
                    return result;
                });
    }

    private void hookOfflineCompletion() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            Class<?> visuals = Reflect.cls(loader, Chrome145.OFFLINE_VISUALS);
            hooks.exact(loader, Chrome145.OFFLINE_COMPLETE, "f",
                    new Class<?>[]{item, visuals}, "chromex:installer:offline", chain -> {
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
                            hooks.error("offline-item APK detection", t);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            hooks.error("install offline APK hook", t);
        }
    }

    private void hookOpenDownload() {
        try {
            Class<?> request = Reflect.cls(loader, Chrome145.OPEN_DOWNLOAD_REQUEST);
            hooks.exact(loader, Chrome145.DOWNLOAD_UTILS, "a", new Class<?>[]{request},
                    "chromex:installer:open", chain -> {
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
                            hooks.error("open-download APK hook", t);
                            return chain.proceed();
                        }
                    });
        } catch (Throwable t) {
            hooks.error("install open-download APK hook", t);
        }
    }

    private void hookControllerCompletion() {
        try {
            Class<?> tab = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
            Class<?> info = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            hooks.exact(loader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                    new Class<?>[]{tab, info, boolean.class},
                    "chromex:installer:controller", chain -> {
                        Object result = chain.proceed();
                        if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return result;
                        try {
                            Object value = chain.getArg(1);
                            if (value != null) {
                                enqueueIfApk(stringField(value, "c"),
                                        stringField(value, "e"), stringField(value, "g"));
                            }
                        } catch (Throwable t) {
                            hooks.error("controller APK detection", t);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            hooks.error("install DownloadController APK hook", t);
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
