package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Version-independent completion support with one primary event source and one fallback. */
final class AdaptiveDownloadObserver {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long BANNER_WINDOW_MS = 4000L;
    private static final long DEDUP_MS = 15_000L;

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChromeX-adaptive-download");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final AtomicLong suppressBannerUntil = new AtomicLong();
    private final AtomicInteger suppressBudget = new AtomicInteger();
    private final long startedAtSeconds = System.currentTimeMillis() / 1000L;

    AdaptiveDownloadObserver(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installBannerSuppression();
        boolean stable = installStableCompletionHook();
        if (!stable) installMediaStoreFallback();
    }

    private boolean installStableCompletionHook() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER);
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                    "chromex:adaptive:download-completed", chain -> {
                        Object info = findDownloadInfo(chain.getArgs().toArray());
                        if (info != null) {
                            onCompleted(stringAccessor(info, "getFilePath"), null,
                                    stringAccessor(info, "getFileName"),
                                    stringAccessor(info, "getMimeType"), "DownloadController");
                        }
                        return chain.proceed();
                    });
            hooks.info("adaptive DownloadController completion hook installed; MediaStore fallback disabled");
            return true;
        } catch (Throwable t) {
            hooks.warn("adaptive DownloadController completion unavailable: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private void installMediaStoreFallback() {
        try {
            ContentResolver resolver = runtime.application.getContentResolver();
            resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true,
                    new ContentObserver(main) {
                        @Override public void onChange(boolean selfChange, Uri uri) {
                            worker.execute(() -> inspect(uri));
                        }
                    });
            hooks.info("adaptive MediaStore completion fallback registered");
        } catch (Throwable t) {
            hooks.error("adaptive MediaStore observer", t);
        }
    }

    private void installBannerSuppression() {
        Method anchor = DexKitResolver.resolveDownloadMessageMethod(runtime, hooks);
        if (anchor == null) return;
        try {
            Class<?> offlineItem = Reflect.cls(runtime.classLoader, Chrome145.OFFLINE_ITEM);
            Class<?> owner = anchor.getDeclaringClass();
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 0 || p[0] != offlineItem) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                String id = "chromex:adaptive:download-banner:" + method.getName() + ":" + installed;
                hooks.method(method, id, chain -> {
                    if (suppressBannerUntil.get() >= System.currentTimeMillis()
                            && takeSuppressionBudget()) return null;
                    return chain.proceed();
                });
                installed++;
            }
            hooks.info("adaptive download banner owner covered: " + owner.getName()
                    + " methods=" + installed);
        } catch (Throwable t) {
            hooks.warn("adaptive banner owner expansion failed: " + t.getClass().getSimpleName());
        }
    }

    private boolean takeSuppressionBudget() {
        while (true) {
            int value = suppressBudget.get();
            if (value <= 0) return false;
            if (suppressBudget.compareAndSet(value, value - 1)) return true;
        }
    }

    private void inspect(Uri changed) {
        Cursor cursor = null;
        try {
            ContentResolver resolver = runtime.application.getContentResolver();
            Uri target = changed != null && "content".equals(changed.getScheme())
                    ? changed : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };
            cursor = resolver.query(target, projection, null, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (cursor == null) return;
            int processed = 0;
            while (cursor.moveToNext() && processed++ < 8) {
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                String owner = cursor.getString(3);
                int pending = cursor.getInt(4);
                long modified = cursor.getLong(5);
                if (!Chrome145.PACKAGE.equals(owner) || pending != 0) continue;
                if (modified + 3 < startedAtSeconds) continue;
                Uri item = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Long.toString(id));
                onCompleted(null, item, name, mime, "MediaStore");
            }
        } catch (Throwable t) {
            hooks.warn("adaptive MediaStore inspect failed: " + t.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void onCompleted(String path, Uri knownUri, String name, String mime, String source) {
        String logicalPath = DownloadNormalizationRegistry.logicalPath(path);
        if (logicalPath != null) {
            path = logicalPath;
            name = new File(logicalPath).getName();
        }
        String key = name == null || name.isBlank()
                ? (path == null ? String.valueOf(knownUri) : path) : name;
        if (!markSeen(key)) return;

        boolean apk = isApk(mime, name != null ? name : path);
        boolean toast = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                || (apk && Config.get(prefs, Config.APK_TOAST));
        if (toast) {
            suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
            suppressBudget.set(4);
            showToast(name == null || name.isBlank() ? "下载文件" : name);
        }
        if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) {
            Uri uri = knownUri;
            if (!InstallerUriResolver.isContent(uri)) {
                InstallerUriResolver.Result resolved = InstallerUriResolver.resolve(
                        runtime.application, runtime.classLoader, path, name);
                if (resolved.uri != null) uri = resolved.uri;
                else {
                    hooks.warn("adaptive APK installer unresolved from " + source
                            + ": " + resolved.detail);
                    return;
                }
            }
            launchInstaller(uri, name == null ? uri.toString() : name);
        }
    }

    private boolean markSeen(String key) {
        long now = System.currentTimeMillis();
        Long old = seen.put(key, now);
        if (old != null && now - old < DEDUP_MS) return false;
        if (seen.size() > 128) seen.entrySet().removeIf(e -> now - e.getValue() > DEDUP_MS);
        return true;
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String stringAccessor(Object value, String getter) {
        if (value == null) return null;
        try {
            Object result = Reflect.call(value, getter);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) { return null; }
    }

    private void showToast(String name) {
        main.post(() -> {
            try { Toast.makeText(runtime.application, "下载完成: " + name, Toast.LENGTH_SHORT).show(); }
            catch (Throwable t) { hooks.warn("adaptive download Toast failed: "
                    + t.getClass().getSimpleName()); }
        });
    }

    private void launchInstaller(Uri uri, String name) {
        if (!InstallerUriResolver.isContent(uri)) {
            hooks.warn("adaptive installer refused non-content URI");
            return;
        }
        main.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                runtime.application.startActivity(intent);
                hooks.info("adaptive APK installer launched: " + name);
            } catch (Throwable t) {
                hooks.error("adaptive APK installer", t);
            }
        });
    }

    private static boolean isApk(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("package-archive")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }
}
