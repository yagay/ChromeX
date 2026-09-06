package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same-name overwrite fallback for unknown/vendor Chromium builds. */
final class AdaptiveSameNameOverwriteHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String DUPLICATE_SEMANTIC =
            "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final int MAX_RETRIES = 24;
    private static final long RETRY_MS = 250L;
    private static final long[] RESIDUAL_CLEANUP_DELAYS_MS = {300L, 1000L, 2500L};
    private static final Object LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    AdaptiveSameNameOverwriteHooks(ChromeRuntime runtime, HookSupport hooks,
                                   SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installDuplicateCapture();
        installCompletionNormalizer();
    }

    private void installDuplicateCapture() {
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:adaptive:overwrite-duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    String path = firstSharedPath(chain.getArgs().toArray());
                    long callback = lastLong(chain.getArgs().toArray());
                    if (path == null || callback == 0L) {
                        hooks.warn("adaptive same-name overwrite: target/callback unavailable");
                        return chain.proceed();
                    }
                    File hint = sharedFile(path);
                    if (hint == null || hint.getName().isBlank()) return chain.proceed();

                    PendingTarget pending = remember(callback, hint.getName(), hint.getParentFile());
                    if (!confirmDuplicate(chain.getThisObject(), callback)) {
                        forget(pending);
                        hooks.warn("adaptive same-name overwrite: callback unresolved; keeping dialog");
                        return chain.proceed();
                    }
                    hooks.info("adaptive same-name overwrite armed: name=" + pending.baseName
                            + " dir=" + safePath(pending.hintDirectory)
                            + " callback=" + callback);
                    return null;
                });
    }

    private void installCompletionNormalizer() {
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:adaptive:overwrite-completed", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (Config.get(prefs, Config.OVERWRITE_DUPLICATE) && info != null) {
                        main.post(() -> normalize(info, 0));
                    }
                    return result;
                });
    }

    private void normalize(Object info, int attempt) {
        if (info == null || pendingCount() == 0) return;
        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(info);
        String path = values.path;
        String name = values.name;
        File direct = sharedFile(path);

        PendingTarget pending = takeMatching(direct, path, name);
        if (pending == null) {
            if (attempt == 0) hooks.warn("adaptive same-name overwrite completion unmatched: path="
                    + safeValue(path) + " name=" + safeValue(name)
                    + " metadata=" + values.detail + " pending=" + pendingCount());
            return;
        }

        File actual = resolveActualFile(pending, direct, path, name);
        if (actual == null) {
            restorePending(pending);
            if (attempt < MAX_RETRIES) {
                main.postDelayed(() -> normalize(info, attempt + 1), RETRY_MS);
            } else {
                hooks.warn("adaptive same-name overwrite actual file unresolved: "
                        + pending.baseName + " dirs=" + directorySummary(pending, direct));
            }
            return;
        }

        File desired;
        try { desired = new File(actual.getParentFile(), pending.baseName).getCanonicalFile(); }
        catch (Throwable t) {
            restorePending(pending);
            return;
        }
        if (!isSharedFile(desired) || !sameParent(actual, desired)) {
            restorePending(pending);
            hooks.warn("adaptive same-name overwrite refused target: " + desired);
            return;
        }

        if (sameFile(actual, desired)) {
            hooks.info("adaptive same-name overwrite already original: " + desired.getPath());
            return;
        }
        if (!DownloadNamePolicy.matchesUniquifiedName(pending.baseName, actual.getName())) {
            restorePending(pending);
            hooks.warn("adaptive same-name overwrite refused unexpected name: " + actual.getName());
            return;
        }

        File oldActual = actual;
        ReplaceResult replace = replaceSameDirectory(actual, desired);
        if (!replace.success) {
            restorePending(pending);
            hooks.warn("adaptive same-name overwrite failed: " + oldActual + " -> " + desired
                    + " :: " + replace.detail);
            return;
        }

        DownloadNormalizationRegistry.register(oldActual, desired);
        rewriteInfoStrings(info, values, oldActual, desired);
        refreshMediaIndex(oldActual, desired, "immediate");
        scheduleResidualCleanup(oldActual, desired);
        hooks.info("adaptive same-name overwrite normalized: " + oldActual.getAbsolutePath()
                + " -> " + desired.getAbsolutePath() + " via " + replace.detail
                + " attempt=" + attempt + " metadata=" + values.detail);
    }

    /**
     * Chromium forks can publish the completed download to MediaStore after the Java completion
     * callback. Keep checking only the exact numbered path that was moved in this transaction.
     * If it reappears, it is a residual of this same overwrite operation, not an unrelated sibling.
     */
    private void scheduleResidualCleanup(File oldActual, File desired) {
        if (oldActual == null || desired == null) return;
        final File oldPath;
        final File newPath;
        try {
            oldPath = oldActual.getCanonicalFile();
            newPath = desired.getCanonicalFile();
        } catch (Throwable ignored) {
            return;
        }
        if (!isSharedFile(oldPath) || !isSharedFile(newPath) || !sameParent(oldPath, newPath)) return;
        if (!DownloadNamePolicy.matchesUniquifiedName(newPath.getName(), oldPath.getName())) return;

        for (long delay : RESIDUAL_CLEANUP_DELAYS_MS) {
            main.postDelayed(() -> cleanupResidual(oldPath, newPath, delay), delay);
        }
    }

    private void cleanupResidual(File oldPath, File desired, long delay) {
        try {
            boolean removedFile = false;
            if (oldPath.exists() && oldPath.isFile()) {
                removedFile = oldPath.delete();
                if (!removedFile) {
                    hooks.warn("adaptive same-name overwrite residual file could not be removed: "
                            + oldPath.getAbsolutePath() + " delay=" + delay);
                }
            }
            int removedRows = removeMediaStorePath(oldPath);
            refreshMediaIndex(oldPath, desired, "delay=" + delay);
            if (removedFile || removedRows > 0) {
                hooks.info("adaptive same-name overwrite residual cleaned: path="
                        + oldPath.getAbsolutePath() + " file=" + removedFile
                        + " mediaRows=" + removedRows + " delay=" + delay);
            }
        } catch (Throwable t) {
            hooks.warn("adaptive same-name overwrite residual cleanup failed: "
                    + t.getClass().getSimpleName() + " delay=" + delay);
        }
    }

    private void refreshMediaIndex(File oldPath, File desired, String phase) {
        try {
            int removedRows = removeMediaStorePath(oldPath);
            String oldValue = oldPath == null ? null : oldPath.getAbsolutePath();
            String newValue = desired == null ? null : desired.getAbsolutePath();
            ArrayList<String> paths = new ArrayList<>(2);
            if (oldValue != null) paths.add(oldValue);
            if (newValue != null) paths.add(newValue);
            if (!paths.isEmpty()) {
                MediaScannerConnection.scanFile(runtime.application,
                        paths.toArray(new String[0]), null, null);
            }
            if (removedRows > 0) {
                hooks.info("adaptive same-name overwrite media index cleaned: old=" + oldValue
                        + " rows=" + removedRows + " phase=" + phase);
            }
        } catch (Throwable t) {
            hooks.warn("adaptive same-name overwrite media refresh failed: "
                    + t.getClass().getSimpleName() + " phase=" + phase);
        }
    }

    private int removeMediaStorePath(File oldPath) {
        if (oldPath == null || oldPath.exists() || runtime.application == null) return 0;
        try {
            ContentResolver resolver = runtime.application.getContentResolver();
            return resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DATA + "=?",
                    new String[]{oldPath.getAbsolutePath()});
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean confirmDuplicate(Object bridge, long callbackId) {
        long ptr = nativePtr(bridge);
        if (ptr == 0L) return false;

        try {
            Class<?> jni = Reflect.cls(runtime.classLoader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni");
            Object instance = Reflect.callStatic(jni, "get");
            if (instance != null) {
                for (String name : new String[]{"onConfirmed", "accepted"}) {
                    try {
                        Reflect.call(instance, name, ptr, callbackId, Boolean.TRUE);
                        hooks.info("adaptive same-name overwrite accepted via generated JNI wrapper");
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        Method semantic = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks, DUPLICATE_SEMANTIC);
        if (semantic == null) return false;
        try {
            Class<?>[] p = semantic.getParameterTypes();
            if (p.length == 3 && p[0] == long.class && p[1] == long.class
                    && p[2] == boolean.class) {
                semantic.invoke(null, ptr, callbackId, true);
                hooks.info("adaptive same-name overwrite accepted via semantic JNI trampoline");
                return true;
            }
            hooks.warn("adaptive same-name overwrite semantic JNI signature unsupported: "
                    + semantic.toGenericString());
        } catch (Throwable t) {
            hooks.warn("adaptive same-name overwrite semantic JNI failed: "
                    + t.getClass().getSimpleName());
        }
        return false;
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) if (arg != null && type.isInstance(arg)) return arg;
        } catch (Throwable ignored) {}
        return null;
    }

    private File resolveActualFile(PendingTarget pending, File direct, String... reported) {
        try {
            if (usableCandidate(pending, direct)) return direct.getCanonicalFile();
            List<File> dirs = downloadDirectories(pending, direct);
            for (String raw : reported) {
                String candidateName = DownloadNamePolicy.fileNameOnly(raw);
                if (candidateName == null) continue;
                for (File dir : dirs) {
                    File candidate = new File(dir, candidateName);
                    if (usableCandidate(pending, candidate)) return candidate.getCanonicalFile();
                }
            }

            File best = null;
            long modified = Long.MIN_VALUE;
            for (File dir : dirs) {
                File[] files;
                try { files = dir.listFiles(); } catch (Throwable ignored) { files = null; }
                if (files == null) continue;
                for (File candidate : files) {
                    if (!usableCandidate(pending, candidate)) continue;
                    if (best == null || candidate.lastModified() > modified) {
                        best = candidate.getCanonicalFile();
                        modified = candidate.lastModified();
                    }
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean usableCandidate(PendingTarget pending, File candidate) {
        if (candidate == null) return false;
        try {
            File file = candidate.getCanonicalFile();
            if (!isSharedFile(file) || !file.isFile()) return false;
            String name = file.getName();
            if (!name.equals(pending.baseName)
                    && !DownloadNamePolicy.matchesUniquifiedName(pending.baseName, name)) return false;
            FileStamp before = pending.before.get(file.getPath());
            return before == null || before.length != file.length()
                    || before.modified != file.lastModified();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rewriteInfoStrings(Object info, AdaptiveDownloadInfo.Values values,
                                    File oldFile, File desired) {
        if (info == null || desired == null) return;
        String oldPath = oldFile == null ? null : oldFile.getAbsolutePath();
        String oldName = oldFile == null ? null : oldFile.getName();
        Class<?> c = info.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(info);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    if ((oldPath != null && oldPath.equals(value))
                            || (values.path != null && values.path.equals(value))) {
                        field.set(info, desired.getAbsolutePath());
                    } else if ((oldName != null && oldName.equals(value))
                            || (values.name != null && values.name.equals(value))) {
                        field.set(info, desired.getName());
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private ReplaceResult replaceSameDirectory(File actual, File desired) {
        File backup = null;
        boolean backedUp = false;
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!sameParent(actual, desired)) return ReplaceResult.fail("different directories");
            if (!actual.isFile()) return ReplaceResult.fail("replacement missing");
            if (desired.exists()) {
                backup = new File(desired.getParentFile(),
                        "." + desired.getName() + ".chromex-backup-" + System.nanoTime());
                move(desired, backup, false);
                backedUp = true;
            }
            try {
                move(actual, desired, true);
            } catch (Throwable moveError) {
                if (backedUp && backup != null && backup.exists()) {
                    try {
                        if (desired.exists()) desired.delete();
                        move(backup, desired, true);
                    } catch (Throwable restoreError) {
                        return ReplaceResult.fail("move/rollback failed");
                    }
                }
                return ReplaceResult.fail("move failed: " + moveError.getClass().getSimpleName());
            }
            if (!desired.isFile() || actual.exists()) return ReplaceResult.fail("verification failed");
            if (backup != null && backup.exists() && !backup.delete()) backup.deleteOnExit();
            return ReplaceResult.ok("transactional same-directory move");
        } catch (Throwable t) {
            if (backedUp && backup != null && backup.exists() && !desired.exists()) {
                try { move(backup, desired, true); } catch (Throwable ignored) {}
            }
            return ReplaceResult.fail(t.getClass().getSimpleName());
        }
    }

    private static void move(File from, File to, boolean replace) throws Exception {
        try {
            if (replace) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from.toPath(), to.toPath());
        }
    }

    private PendingTarget remember(long callback, String baseName, File hintDirectory) {
        long now = System.currentTimeMillis();
        PendingTarget pending = new PendingTarget(callback, baseName,
                canonicalDirectory(hintDirectory), now, snapshot(baseName, hintDirectory));
        synchronized (LOCK) {
            pruneLocked(now);
            PENDING.removeIf(old -> old.callback == callback);
            PENDING.add(pending);
            while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
        }
        return pending;
    }

    private Map<String, FileStamp> snapshot(String baseName, File hintDirectory) {
        LinkedHashMap<String, FileStamp> out = new LinkedHashMap<>();
        PendingTarget probe = new PendingTarget(0L, baseName,
                canonicalDirectory(hintDirectory), System.currentTimeMillis(), out);
        for (File dir : downloadDirectories(probe, null)) {
            File[] files;
            try { files = dir.listFiles(); } catch (Throwable ignored) { files = null; }
            if (files == null) continue;
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                String name = file.getName();
                if (!name.equals(baseName)
                        && !DownloadNamePolicy.matchesUniquifiedName(baseName, name)) continue;
                try {
                    File canonical = file.getCanonicalFile();
                    out.put(canonical.getPath(), new FileStamp(canonical.length(), canonical.lastModified()));
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private List<File> downloadDirectories(PendingTarget pending, File direct) {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        addDir(out, direct == null ? null : direct.getParentFile());
        addDir(out, pending == null ? null : pending.hintDirectory);
        try { addDir(out, runtime.application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        try { addDir(out, new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        return new ArrayList<>(out.values());
    }

    private PendingTarget takeMatching(File actual, String... reported) {
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            PendingTarget found = null;
            for (PendingTarget pending : PENDING) {
                boolean match = actual != null && matches(pending.baseName, actual.getName());
                if (!match) {
                    for (String value : reported) {
                        if (matches(pending.baseName, DownloadNamePolicy.fileNameOnly(value))) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!match) continue;
                if (found != null) return null;
                found = pending;
            }
            if (found != null) PENDING.remove(found);
            return found;
        }
    }

    private static boolean matches(String base, String value) {
        return value != null && (base.equals(value) || DownloadNamePolicy.matchesUniquifiedName(base, value));
    }

    private void restorePending(PendingTarget pending) {
        if (pending == null) return;
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            PENDING.removeIf(old -> old.callback == pending.callback);
            PENDING.add(pending);
        }
    }

    private static void forget(PendingTarget pending) {
        synchronized (LOCK) { PENDING.remove(pending); }
    }

    private static int pendingCount() {
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            return PENDING.size();
        }
    }

    private static void pruneLocked(long now) {
        Iterator<PendingTarget> it = PENDING.iterator();
        while (it.hasNext()) if (now - it.next().time > PENDING_TTL_MS) it.remove();
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        Field found = null;
        Class<?> c = bridge.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (value == 0L) continue;
                    if (found != null) return 0L;
                    found = field;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        if (found == null) return 0L;
        try { return found.getLong(bridge); } catch (Throwable ignored) { return 0L; }
    }

    private static String firstSharedPath(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).startsWith("/")) return (String) arg;
        }
        return null;
    }

    private static long lastLong(Object[] args) {
        if (args == null) return 0L;
        for (int i = args.length - 1; i >= 0; i--) if (args[i] instanceof Long) return (Long) args[i];
        return 0L;
    }

    private static File sharedFile(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith("/")) return null;
        try {
            File file = new File(raw).getCanonicalFile();
            return isSharedFile(file) ? file : null;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean isSharedFile(File file) {
        if (file == null) return false;
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            return file.getCanonicalPath().startsWith(root.getPath() + File.separator);
        } catch (Throwable ignored) { return false; }
    }

    private static File canonicalDirectory(File dir) {
        if (dir == null) return null;
        try {
            File value = dir.getCanonicalFile();
            return isSharedFile(value) ? value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static void addDir(Map<String, File> out, File dir) {
        File value = canonicalDirectory(dir);
        if (value != null) out.put(value.getPath(), value);
    }

    private static boolean sameFile(File a, File b) {
        try { return a != null && b != null && a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean sameParent(File a, File b) {
        try {
            File ap = a == null ? null : a.getCanonicalFile().getParentFile();
            File bp = b == null ? null : b.getCanonicalFile().getParentFile();
            return ap != null && ap.equals(bp);
        } catch (Throwable ignored) { return false; }
    }

    private String directorySummary(PendingTarget pending, File direct) {
        StringBuilder out = new StringBuilder();
        for (File dir : downloadDirectories(pending, direct)) {
            if (out.length() > 0) out.append('|');
            out.append(dir.getPath());
        }
        return out.toString();
    }

    private static String safeValue(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String safePath(File file) {
        return file == null ? "<none>" : file.getAbsolutePath();
    }

    private static final class PendingTarget {
        final long callback;
        final String baseName;
        final File hintDirectory;
        final long time;
        final Map<String, FileStamp> before;
        PendingTarget(long callback, String baseName, File hintDirectory,
                      long time, Map<String, FileStamp> before) {
            this.callback = callback;
            this.baseName = baseName;
            this.hintDirectory = hintDirectory;
            this.time = time;
            this.before = before;
        }
    }

    private static final class FileStamp {
        final long length;
        final long modified;
        FileStamp(long length, long modified) { this.length = length; this.modified = modified; }
    }

    private static final class ReplaceResult {
        final boolean success;
        final String detail;
        private ReplaceResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
        static ReplaceResult ok(String detail) { return new ReplaceResult(true, detail); }
        static ReplaceResult fail(String detail) { return new ReplaceResult(false, detail); }
    }
}
