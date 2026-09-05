package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Opt-in same-name download overwrite support.
 *
 * Chromium's duplicate dialog is not the final filename decision point. Production Chrome can
 * accept that dialog and then still run native DownloadPathReservationTracker/UNIQUIFY, producing
 * "file (1).ext". Therefore this hook uses the dialog only to remember the user's desired original
 * target and auto-accept the duplicate request. On verified Chrome 152 it also intercepts the
 * completed DownloadInfo before the rest of ChromeX's completion hooks, renames a generated
 * " (n)" target back to the desired original name, and updates DownloadInfo to the final path.
 *
 * No existing file is deleted at dialog time. Replacement happens only after the new download is
 * complete, so a failed/cancelled download cannot destroy the old file.
 */
final class OverwriteDownloadHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final Object PENDING_LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    OverwriteDownloadHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installDuplicateCapture();
        if (Chrome152.matches(runtime)) installChrome152CompletionNormalizer();
    }

    private void installDuplicateCapture() {
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:download:overwrite-duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                        hooks.warn("same-name overwrite: duplicate bridge path unavailable");
                        return chain.proceed();
                    }

                    File desired = sharedFile((String) chain.getArg(1));
                    if (desired == null) {
                        hooks.warn("same-name overwrite: desired path is not a safe shared-storage file");
                        return chain.proceed();
                    }
                    long callbackId = lastLong(chain.getArgs().toArray());
                    if (callbackId == 0L) {
                        hooks.warn("same-name overwrite: duplicate callback id unavailable");
                        return chain.proceed();
                    }

                    PendingTarget pending = remember(desired);
                    if (!confirmDuplicate(chain.getThisObject(), callbackId)) {
                        forget(pending);
                        hooks.warn("same-name overwrite: duplicate callback unresolved; keeping Chrome dialog");
                        return chain.proceed();
                    }

                    hooks.info("same-name overwrite armed: " + desired.getName());
                    return null;
                });
    }

    /**
     * This hook is installed before Chrome152Hooks, so after normalization chain.proceed() lets the
     * existing Toast/APK-installer hooks see the corrected DownloadInfo path/name.
     */
    private void installChrome152CompletionNormalizer() {
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:download:overwrite-completed", chain -> {
                    if (Config.get(prefs, Config.OVERWRITE_DUPLICATE)) {
                        try {
                            Object info = findDownloadInfo(chain.getArgs().toArray());
                            normalizeDownloadInfo(info);
                        } catch (Throwable t) {
                            hooks.warn("same-name overwrite completion failed: "
                                    + t.getClass().getSimpleName());
                        }
                    }
                    return chain.proceed();
                });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void normalizeDownloadInfo(Object info) {
        if (info == null) return;
        String rawPath = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);
        File actual = sharedFile(rawPath);
        if (actual == null) return;

        PendingTarget pending = takeMatching(actual);
        if (pending == null) return;
        File desired = pending.desired;

        if (sameFile(actual, desired)) {
            bestEffortUpdateDownloadInfo(info, desired);
            hooks.info("same-name overwrite completed with original name: " + desired.getName());
            return;
        }

        MoveResult moved = replaceCompletedFile(runtime.application, actual, desired);
        if (!moved.success) {
            // Keep the pending entry for another completion signal/retry if Chrome fires one.
            remember(desired);
            hooks.warn("same-name overwrite normalization failed: " + actual.getName()
                    + " -> " + desired.getName() + " :: " + moved.detail);
            return;
        }

        bestEffortUpdateDownloadInfo(info, desired);
        hooks.info("same-name overwrite normalized: " + actual.getName()
                + " -> " + desired.getName() + " via " + moved.detail);
    }

    private void bestEffortUpdateDownloadInfo(Object info, File desired) {
        try {
            Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, desired.getPath());
        } catch (Throwable t) {
            hooks.warn("same-name overwrite: DownloadInfo path update unavailable: "
                    + t.getClass().getSimpleName());
        }
        try {
            Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, desired.getName());
        } catch (Throwable t) {
            hooks.warn("same-name overwrite: DownloadInfo name update unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private MoveResult replaceCompletedFile(Context context, File actual, File desired) {
        if (context == null) return MoveResult.fail("missing context");
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!sameParent(actual, desired)) return MoveResult.fail("different directories");
            if (actual.isDirectory() || desired.isDirectory()) return MoveResult.fail("directory target");

            ContentResolver resolver = context.getContentResolver();

            // First remove the old original target only now, after the replacement download has
            // completed successfully. Also clear stale MediaStore metadata for that original path.
            if (!deleteReplaceTarget(resolver, desired)) {
                return MoveResult.fail("old original could not be removed");
            }

            // Preferred scoped-storage path: rename Chrome's own completed MediaStore row. Updating
            // DISPLAY_NAME keeps the row/ownership/URI coherent and normally renames the file too.
            Uri actualRow = findMediaRow(resolver, actual.getPath());
            if (actualRow != null) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, desired.getName());
                    int changed = resolver.update(actualRow, values, null, null);
                    if (changed > 0 && verifyMoved(actual, desired)) {
                        return MoveResult.ok("MediaStore");
                    }
                } catch (Throwable ignored) {}
            }

            // Filesystem fallback. Chrome owns the freshly downloaded file on the current device,
            // so this is expected to work even when no MediaStore row was discoverable yet.
            if (!actual.exists()) return MoveResult.fail("completed file is missing");
            try {
                Files.move(actual.toPath(), desired.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable first) {
                if (!actual.renameTo(desired)) {
                    return MoveResult.fail("move denied: " + first.getClass().getSimpleName());
                }
            }

            if (!desired.exists() || actual.exists()) {
                return MoveResult.fail("move could not be verified");
            }

            // Remove a stale row that still points to the old uniquified path, then request a scan
            // of the final original-name target. These are metadata repairs; the file move is done.
            deleteMediaStoreRow(resolver, actual.getPath());
            try {
                MediaScannerConnection.scanFile(context,
                        new String[]{desired.getPath()}, null, null);
            } catch (Throwable ignored) {}
            return MoveResult.ok("filesystem");
        } catch (Throwable t) {
            return MoveResult.fail(t.getClass().getSimpleName());
        }
    }

    private boolean deleteReplaceTarget(ContentResolver resolver, File desired) {
        try {
            if (desired.exists() && desired.isDirectory()) return false;
            deleteMediaStoreRow(resolver, desired.getPath());
            if (desired.exists() && !desired.delete()) return false;
            return !desired.exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Uri findMediaRow(ContentResolver resolver, String absolutePath) {
        if (resolver == null || absolutePath == null) return null;
        Cursor cursor = null;
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            cursor = resolver.query(files,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + "=?",
                    new String[]{absolutePath}, null);
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(files, cursor.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private boolean deleteMediaStoreRow(ContentResolver resolver, String absolutePath) {
        if (resolver == null || absolutePath == null) return false;
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            return resolver.delete(files,
                    MediaStore.MediaColumns.DATA + "=?", new String[]{absolutePath}) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean verifyMoved(File actual, File desired) {
        try {
            return desired.exists() && !actual.exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File sharedFile(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || rawPath.startsWith("content://")) return null;
        try {
            File file = new File(rawPath).getCanonicalFile();
            File external = Environment.getExternalStorageDirectory().getCanonicalFile();
            String root = external.getPath();
            String path = file.getPath();
            if (!path.startsWith(root + File.separator)) return null;
            return file;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameFile(File a, File b) {
        return a != null && b != null && a.getPath().equals(b.getPath());
    }

    private static boolean sameParent(File a, File b) {
        if (a == null || b == null) return false;
        File ap = a.getParentFile();
        File bp = b.getParentFile();
        return ap != null && bp != null && ap.getPath().equals(bp.getPath());
    }

    private static PendingTarget remember(File desired) {
        PendingTarget pending = new PendingTarget(desired, System.currentTimeMillis());
        synchronized (PENDING_LOCK) {
            pruneLocked(pending.time);
            // A newer request for the same target supersedes an older failed/stale request.
            PENDING.removeIf(old -> old.desired.getPath().equals(desired.getPath()));
            PENDING.add(pending);
            while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
        }
        return pending;
    }

    private static void forget(PendingTarget pending) {
        if (pending == null) return;
        synchronized (PENDING_LOCK) {
            PENDING.remove(pending);
        }
    }

    private static PendingTarget takeMatching(File actual) {
        long now = System.currentTimeMillis();
        synchronized (PENDING_LOCK) {
            pruneLocked(now);
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                PendingTarget pending = PENDING.get(i);
                if (matchesUniquified(pending.desired, actual)) {
                    PENDING.remove(i);
                    return pending;
                }
            }
        }
        return null;
    }

    private static void pruneLocked(long now) {
        Iterator<PendingTarget> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingTarget value = it.next();
            if (now - value.time > PENDING_TTL_MS) it.remove();
        }
    }

    private static boolean matchesUniquified(File desired, File actual) {
        try {
            if (!sameParent(desired, actual)) return false;
            String wanted = desired.getName();
            String got = actual.getName();
            if (wanted.equals(got)) return true;

            int dot = wanted.lastIndexOf('.');
            String stem = dot > 0 ? wanted.substring(0, dot) : wanted;
            String ext = dot > 0 ? wanted.substring(dot) : "";
            String prefix = stem + " (";
            String suffix = ")" + ext;
            if (!got.startsWith(prefix) || !got.endsWith(suffix)) return false;
            int numberEnd = got.length() - suffix.length();
            if (numberEnd <= prefix.length()) return false;
            String number = got.substring(prefix.length(), numberEnd);
            for (int i = 0; i < number.length(); i++) {
                if (!Character.isDigit(number.charAt(i))) return false;
            }
            return !number.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
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

    private static String stringField(Object owner, String name) {
        try {
            Object value = Reflect.get(owner, name);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class PendingTarget {
        final File desired;
        final long time;

        PendingTarget(File desired, long time) {
            this.desired = desired;
            this.time = time;
        }
    }

    private static final class MoveResult {
        final boolean success;
        final String detail;

        private MoveResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }

        static MoveResult ok(String detail) {
            return new MoveResult(true, detail);
        }

        static MoveResult fail(String detail) {
            return new MoveResult(false, detail);
        }
    }
}
