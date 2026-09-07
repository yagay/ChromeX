package com.yagay.chromex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** ChromeX-owned foreground downloader for takeover-safe HTTP/HTTPS GET requests. */
public final class ChromeXDownloadService extends Service {
    static final String ACTION_DOWNLOAD = "com.yagay.chromex.action.DOWNLOAD";
    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_COOKIE = "cookie";
    static final String EXTRA_REFERER = "referer";
    static final String EXTRA_USER_AGENT = "user_agent";
    static final String EXTRA_GUID = "guid";
    static final String EXTRA_RECEIVER = "receiver";
    static final int RESULT_ACCEPTED = 1;
    static final int RESULT_REJECTED = 2;

    private static final String CHANNEL = "chromex_downloads";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(4100);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ChromeX-downloader");
        t.setDaemon(true);
        return t;
    });

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_DOWNLOAD.equals(intent.getAction())) return START_NOT_STICKY;
        int notificationId = NEXT_ID.incrementAndGet();
        String name = safeName(intent.getStringExtra(EXTRA_NAME));
        startForeground(notificationId, notification(name, "正在连接…", 0, true));
        workers.execute(() -> download(intent, notificationId, startId));
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        workers.shutdownNow();
        super.onDestroy();
    }

    private void download(Intent intent, int notificationId, int startId) {
        String url = intent.getStringExtra(EXTRA_URL);
        String name = safeName(intent.getStringExtra(EXTRA_NAME));
        String mime = blank(intent.getStringExtra(EXTRA_MIME));
        String cookie = blank(intent.getStringExtra(EXTRA_COOKIE));
        String referer = blank(intent.getStringExtra(EXTRA_REFERER));
        String userAgent = blank(intent.getStringExtra(EXTRA_USER_AGENT));
        ResultReceiver receiver = getReceiver(intent);
        HttpURLConnection connection = null;
        Uri itemUri = null;
        boolean accepted = false;
        try {
            if (url == null || name == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                reject(receiver, "invalid-request");
                return;
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("GET");
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (referer != null) connection.setRequestProperty("Referer", referer);
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
            connection.connect();

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                reject(receiver, "http-" + status);
                update(notificationId, name, "交回 Chrome（HTTP " + status + "）", 0, false);
                return;
            }
            if (mime == null) mime = blank(connection.getContentType());
            long total = connection.getContentLengthLong();

            deleteExisting(name);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) throw new IllegalStateException("MediaStore insert failed");

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream raw = resolver.openOutputStream(itemUri, "w");
                 OutputStream out = raw == null ? null : new BufferedOutputStream(raw)) {
                if (out == null) throw new IllegalStateException("openOutputStream failed");
                accepted = true;
                if (receiver != null) receiver.send(RESULT_ACCEPTED, null);
                byte[] buffer = new byte[128 * 1024];
                long done = 0;
                long lastUi = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    out.write(buffer, 0, read);
                    done += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUi >= 500) {
                        int progress = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                        update(notificationId, name,
                                total > 0 ? progress + "%" : human(done), progress, total <= 0);
                        lastUi = now;
                    }
                }
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(itemUri, done, null, null);
            update(notificationId, name, "下载完成", 100, false);
            stopSelf(startId);
        } catch (Throwable t) {
            if (!accepted) reject(receiver, t.getClass().getSimpleName());
            if (itemUri != null) {
                try { getContentResolver().delete(itemUri, null, null); } catch (Throwable ignored) {}
            }
            update(notificationId, name, accepted ? "下载失败" : "交回 Chrome", 0, false);
            stopSelf(startId);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deleteExisting(String name) {
        // Root is optional. It lets ChromeX replace an existing file created by Chrome/another app
        // without MediaStore ownership prompts. Failure simply falls through to MediaStore cleanup.
        try {
            File target = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), name).getCanonicalFile();
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getCanonicalFile();
            if (target.getParentFile() != null && target.getParentFile().equals(dir) && target.exists()) {
                try {
                    Process p = new ProcessBuilder("su", "-c", "rm -f -- " + shellQuote(target.getAbsolutePath()))
                            .redirectErrorStream(true).start();
                    p.waitFor();
                } catch (Throwable ignored) {
                    try { target.delete(); } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            String where = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where,
                    new String[]{name, Environment.DIRECTORY_DOWNLOADS + "/"});
        } catch (Throwable ignored) {}
    }

    private void reject(ResultReceiver receiver, String reason) {
        if (receiver == null) return;
        android.os.Bundle b = new android.os.Bundle();
        b.putString("reason", reason);
        receiver.send(RESULT_REJECTED, b);
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver getReceiver(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RECEIVER);
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "ChromeX 下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("ChromeX 接管的 Chrome 下载任务");
        nm.createNotificationChannel(channel);
    }

    private Notification notification(String name, String text, int progress, boolean indeterminate) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setContentTitle(name == null ? "ChromeX 下载" : name)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(indeterminate || progress < 100)
                .setOnlyAlertOnce(true);
        if (indeterminate) b.setProgress(0, 0, true);
        else if (progress > 0 && progress < 100) b.setProgress(100, progress, false);
        return b.build();
    }

    private void update(int id, String name, String text, int progress, boolean indeterminate) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, notification(name, text, progress, indeterminate));
    }

    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "download";
        String name = DownloadNamePolicy.fileNameOnly(raw);
        return name == null || name.isBlank() ? "download" : name;
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static String human(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
        if (bytes >= 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
