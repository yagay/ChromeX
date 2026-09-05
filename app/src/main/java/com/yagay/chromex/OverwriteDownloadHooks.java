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
 * Chromium accepts the duplicate dialog before native DownloadPathReservationTracker has finished
 * choosing the final target. Production Chrome may therefore still turn "file.ext" into
 * "file (1).ext" after the dialog was accepted. This class remembers the original desired target
 * at dialog time and normalizes the completed file back to that target before ChromeX's normal
 * completion/Toast/APK-installer hooks run.
 *
 * No old file is deleted until a new completed replacement has been located and verified.
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

    /** Installed before Chrome152Hooks so later completion hooks see the corrected DownloadInfo. */
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

        String rawPath = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);   // targetFilePath
        String reportName = stringField(info, Chrome152.DOWNLOAD_INFO_NAME); // fileNameToReportUser
        String reportName2 = stringField(info, "f");
        File directActual = sharedFile(rawPath);

        PendingTarget pending = takeMatching(directActual, rawPath, reportName, reportName2);
        if (pending == null) {
            if (pendingCount() > 0) {
                hooks.warn("same-name overwrite completion unmatched: path=" + safeName(rawPath)
                        + " name=" + safeText(reportName)
                        + " alt=" + safeText(reportName2)
                        + " pending=" + pendingCount());
            }
            return;
        }

        File desired = pending.desired;
        File actual = resolveActualFile(runtime.application, desired, directActual,
                rawPath, reportName, reportName2);
        if (actual == null) {
            remember(desired);
            hooks.warn("same-name overwrite actual file unresolved: path=" + safeName(rawPath)
                    + " name=" + safeText(reportName)
                    + " desired=" + desired.getName());
            return;
        }

        if (sameFile(actual, desired)) {
            bestEffortUpdateDownloadInfo(info, desired);
            hooks.info("same-name overwrite completed with original name: " + desired.getName());
            return;
        }

        if (!matchesUniquifiedName(desired.getName(), actual.getName())) {
            remember(desired);
            hooks.warn("same-name overwrite refused unexpected completed name: "
                    + actual.getName() + " expected " + desired.getName());
            return;
        }

        MoveResult moved = replaceCompletedFile(runtime.application, actual, desired);
        if (!moved.success) {
            remember(desired);
            hooks.warn("same-name overwrite normalization failed: " + actual.getName()
                    + " -> " + desired.getName() + " :: " + moved.detail);
            return;
        }

        bestEffortUpdateDownloadInfo(info, desired);
        hooks.info("same-name overwrite normalized: " + actual.getName()
                + " -> " + desired.getName() + " via " + moved.detail);
    }

    private File resolveActualFile(Context context, File desired, File direct,
                                   String rawPath, String name1, String name2) {
        try {
            if (direct != null && direct.exists() && !direct.isDirectory()) return direct;

            File parent = desired.getParentFile();
            for (String value : new String[]{fileNameOnly(rawPath), fileNameOnly(name1), fileNameOnly(name2)}) {
                if (value == null || value.isBlank() || parent == null) continue;
                File candidate = new File(parent, value).getCanonicalFile();
                if (isSharedFile(candidate) && candidate.exists() && !candidate.isDirectory()) {
                    return candidate;
                }
            }

            if (context != null) {
                ContentResolver resolver = context.getContentResolver();
                for (String value : new String[]{fileNameOnly(name1), fileNameOnly(name2), fileNameOnly(rawPath)}) {
                    if (value == null || value.isBlank()) continue;
                    File media = findMediaFileByDisplayName(resolver, value, desired);
                    if (media != null && media.exists() && !media.isDirectory()) return media;
                }
            }

            return direct != null && direct.exists() ? direct : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private File findMediaFileByDisplayName(ContentResolver resolver, String displayName, File desired) {
        if (resolver == null || displayName == null || displayName.isBlank()) return null;
        Cursor cursor = null;
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            cursor = resolver.query(files,
                    new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{displayName},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            File fallback = null;
            while (cursor != null && cursor.moveToNext()) {
                int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (dataIndex < 0) continue;
                File file = sharedFile(cursor.getString(dataIndex));
                if (file == null || !file.exists() || file.isDirectory()) continue;
                if (sameParent(file, desired)) return file;
                if (fallback == null) fallback = file;
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void bestEffortUpdateDownloadInfo(Object info, File desired) {
        try {
            Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, desired.getPath());
        } catch (Throwable t) {
            hooks.warn("same-name overwrite: DownloadInfo path update unavailable: "
                    + t.getClass().getSimpleName());
        }
        try {
            // Chrome 152 createDownloadInfo copies fileNameToReportUser into both e and f.
            Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, desired.getName());
            Reflect.set(info, "f", desired.getName());
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
            if (!isSharedFile(actual) || !isSharedFile(desired)) {
                return MoveResult.fail("outside shared storage");
            }
            if (!actual.exists()) return MoveResult.fail("completed file is missing");
            if (actual.isDirectory() || desired.isDirectory()) return MoveResult.fail("directory target");

            ContentResolver resolver = context.getContentResolver();
            if (!deleteReplaceTarget(resolver, desired)) {
                return MoveResult.fail("old original could not be removed");
            }

            // If both files are in the same directory, first try renaming Chrome's own MediaStore row.
            if (sameParent(actual, desired)) {
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
            }

            // Filesystem fallback also handles path aliases or a different shared-storage directory.
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
            return isSharedFile(file) ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSharedFile(File file) {
        if (file == null) return false;
        try {
            File external = Environment.getExternalStorageDirectory().getCanonicalFile();
            String root = external.getPath();
            String path = file.getCanonicalPath();
            return path.startsWith(root + File.separator);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a != null && b != null && a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sameParent(File a, File b) {
        if (a == null || b == null) return false;
        try {
            File ap = a.getCanonicalFile().getParentFile();
            File bp = b.getCanonicalFile().getParentFile();
            return ap != null && bp != null && ap.getPath().equals(bp.getPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static PendingTarget remember(File desired) {
        PendingTarget pending = new PendingTarget(desired, System.currentTimeMillis());
        synchronized (PENDING_LOCK) {
            pruneLocked(pending.time);
            PENDING.removeIf(old -> sameFile(old.desired, desired));
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

    private static int pendingCount() {
        synchronized (PENDING_LOCK) {
            pruneLocked(System.currentTimeMillis());
            return PENDING.size();
        }
    }

    private static PendingTarget takeMatching(File actual, String... reportedValues) {
        long now = System.currentTimeMillis();
        synchronized (PENDING_LOCK) {
            pruneLocked(now);

            // Strongest match: actual target path in the same canonical directory.
            if (actual != null) {
                for (int i = PENDING.size() - 1; i >= 0; i--) {
                    PendingTarget pending = PENDING.get(i);
                    if (sameParent(pending.desired, actual)
                            && matchesUniquifiedName(pending.desired.getName(), actual.getName())) {
                        PENDING.remove(i);
                        return pending;
                    }
                }
            }

            // Chrome 152 exposes fileNameToReportUser in DownloadInfo.e/f. Use that name even when
            // targetFilePath uses a storage alias or cannot be resolved through java.io.File.
            PendingTarget only = null;
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                PendingTarget pending = PENDING.get(i);
                boolean match = false;
                for (String value : reportedValues) {
                    String name = fileNameOnly(value);
                    if (name != null && matchesUniquifiedName(pending.desired.getName(), name)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                if (only != null) return null; // ambiguous basename across pending downloads
                only = pending;
            }
            if (only != null) {
                PENDING.remove(only);
                return only;
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

    private static boolean matchesUniquifiedName(String wanted, String got) {
        try {
            if (wanted == null || got == null) return false;
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
            if (number.isEmpty()) return false;
            for (int i = 0; i < number.length(); i++) {
                if (!Character.isDigit(number.charAt(i))) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean confirmDuplicate(Object bridge, long callbackId) {
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

    private static String fileNameOnly(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.startsWith("content://")) {
                Uri uri = Uri.parse(value);
                String segment = uri.getLastPathSegment();
                return segment == null || segment.isBlank() ? null : segment;
            }
            return new File(value).getName();
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String safeName(String value) {
        String name = fileNameOnly(value);
        return name == null || name.isBlank() ? "<none>" : name;
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) return "<none>";
        String name = fileNameOnly(value);
        return name == null ? "<value>" : name;
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
