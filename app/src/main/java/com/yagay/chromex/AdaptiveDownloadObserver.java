package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chrome-version-independent completion fallback for unknown builds. It observes completed rows in
 * MediaStore instead of reading obfuscated DownloadInfo fields. This is intentionally used only by
 * the adaptive profile so verified release profiles keep their lower-latency native completion path.
 */
final class AdaptiveDownloadObserver {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long BANNER_WINDOW_MS = 4000L;
    private static final long DEDUP_MS = 120_000L;

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChromeX-media-observer");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Long> seen = new ConcurrentHashMap<>();
    private final AtomicLong suppressBannerUntil = new AtomicLong(0L);
    private final long startedAtSeconds = System.currentTimeMillis() / 1000L;

    AdaptiveDownloadObserver(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installBannerSuppression();
        try {
            ContentResolver resolver = runtime.application.getContentResolver();
            resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true,
                    new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            worker.execute(() -> inspect(uri));
                        }
                    });
            hooks.info("adaptive download observer registered on MediaStore.Downloads");
        } catch (Throwable t) {
            hooks.error("adaptive MediaStore observer", t);
        }
    }

    private void installBannerSuppression() {
        Method message = DexKitResolver.resolveDownloadMessageMethod(runtime, hooks);
        if (message == null) return;
        hooks.method(message, "chromex:adaptive:download-banner", chain -> {
            if (suppressBannerUntil.get() >= System.currentTimeMillis()) return null;
            return chain.proceed();
        });
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
                if (!markSeen(id)) continue;

                Uri item = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Long.toString(id));
                onCompleted(item, name, mime);
            }
        } catch (Throwable t) {
            hooks.warn("adaptive MediaStore inspect failed: " + t.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean markSeen(long id) {
        long now = System.currentTimeMillis();
        Long old = seen.putIfAbsent(id, now);
        if (old != null && now - old < DEDUP_MS) return false;
        seen.put(id, now);
        if (seen.size() > 128) {
            seen.entrySet().removeIf(e -> now - e.getValue() > DEDUP_MS);
        }
        return true;
    }

    private void onCompleted(Uri uri, String name, String mime) {
        boolean apk = isApk(mime, name);
        boolean toast = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                || (apk && Config.get(prefs, Config.APK_TOAST));
        if (toast) {
            suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
            showToast(name == null || name.isBlank() ? "下载文件" : name);
        }
        if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) {
            launchInstaller(uri, name == null ? uri.toString() : name);
        }
    }

    private void showToast(String name) {
        main.post(() -> {
            try {
                Toast.makeText(runtime.application, "下载完成: " + name,
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                hooks.warn("adaptive download Toast failed: " + t.getClass().getSimpleName());
            }
        });
    }

    private void launchInstaller(Uri uri, String name) {
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
