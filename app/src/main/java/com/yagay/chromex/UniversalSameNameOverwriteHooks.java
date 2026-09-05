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

/** One same-name overwrite fallback for every Chromium-family build. */
final class UniversalSameNameOverwriteHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String DUPLICATE_SEMANTIC =
            "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final int MAX_RETRIES = 24;
    private static final long RETRY_MS = 250L;
    private static final long[] RESIDUAL_DELAYS_MS = {300L, 1000L, 2500L};
    private static final Object LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    UniversalSameNameOverwriteHooks(ChromiumProfile profile, ChromeRuntime runtime,
                                    HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installDuplicateCapture();
        installCompletionNormalizer(Chrome145.DOWNLOAD_CONTROLLER,
                "chromex:universal:overwrite:controller-completed");
        installCompletionNormalizer(Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "chromex:universal:overwrite:manager-completed");
    }

    private void installDuplicateCapture() {
        if (!hasMethod(DUPLICATE_BRIDGE, "showDialog")) return;
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:universal:overwrite:duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    DuplicateTarget target = duplicateTarget(chain.getArgs().toArray());
                    long callback = lastLong(chain.getArgs().toArray());
                    if (target == null || callback == 0L) {
                        hooks.warn("same-name overwrite: duplicate target/callback unavailable");
                        return chain.proceed();
                    }

                    PendingTarget pending = remember(
                            callback, target.baseName, target.hintDirectory);
                    if (!confirmDuplicate(chain.getThisObject(), callback)) {
                        forget(pending);
                        hooks.warn("same-name overwrite: duplicate callback unresolved; preserving dialog");
                        return chain.proceed();
                    }
                    hooks.info("same-name overwrite armed: name=" + pending.baseName
                            + " dir=" + safePath(pending.hintDirectory)
                            + " callback=" + callback);
                    return null;
                });
    }

    private void installCompletionNormalizer(String owner, String id) {
        if (!hasMethod(owner, "onDownloadCompleted")) return;
        hooks.all(runtime.classLoader, owner, "onDownloadCompleted", id, chain -> {
            Object info = DownloadInfoAccessor.find(chain.getArgs().toArray(), runtime.classLoader);
            Object result = chain.proceed();
            if (Config.get(prefs, Config.OVERWRITE_DUPLICATE) && info != null) {
                main.post(() -> normalize(info, 0));
            }
            return result;
        });
    }

    private void normalize(Object info, int attempt) {
        if (info == null || pendingCount() == 0) return;
        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
        File direct = sharedFile(values.path);
        PendingTarget pending = takeMatching(direct, values.path, values.name);
        if (pending == null) {
            if (attempt == 0) {
                hooks.warn("same-name overwrite completion unmatched: path=" + safeValue(values.path)
                        + " name=" + safeValue(values.name)
                        + " metadata=" + values.detail + " pending=" + pendingCount());
            }
            return;
        }

        File actual = resolveActualFile(pending, direct, values.path, values.name);
        if (actual == null) {
            restorePending(pending);
            if (attempt < MAX_RETRIES) {
                main.postDelayed(() -> normalize(info, attempt + 1), RETRY_MS);
            } else {
                hooks.warn("same-name overwrite actual file unresolved: " + pending.baseName
                        + " dirs=" + directorySummary(pending, direct));
            }
            return;
        }

        final File desired;
        try {
            desired = new File(actual.getParentFile(), pending.baseName).getCanonicalFile();
        } catch (Throwable t) {
            restorePending(pending);
            return;
        }
        if (!isSharedFile(desired) || !sameParent(actual, desired)) {
            restorePending(pending);
            hooks.warn("same-name overwrite refused target: " + desired);
            return;
        }

        if (sameFile(actual, desired)) {
            DownloadInfoAccessor.rewrite(info, profile, desired);
            hooks.info("same-name overwrite already original: " + desired.getAbsolutePath());
            return;
        }
        if (!DownloadNamePolicy.matchesUniquifiedName(pending.baseName, actual.getName())) {
            restorePending(pending);
            hooks.warn("same-name overwrite refused unexpected name: " + actual.getName());
            return;
        }

        File oldActual = actual;
        ReplaceResult replace = replaceSameDirectory(actual, desired);
        if (!replace.success) {
            restorePending(pending);
            hooks.warn("same-name overwrite failed: " + oldActual + " -> " + desired
                    + " :: " + replace.detail);
            return;
        }

        DownloadNormalizationRegistry.register(oldActual, desired);
        boolean metadataChanged = DownloadInfoAccessor.rewrite(info, profile, desired);
        refreshMediaIndex(oldActual, desired, "immediate");
        scheduleResidualCleanup(oldActual, desired);
        hooks.info("same-name overwrite normalized: " + oldActual.getAbsolutePath()
                + " -> " + desired.getAbsolutePath() + " via " + replace.detail
                + " attempt=" + attempt + " metadata=" + values.detail
                + " metadataChanged=" + metadataChanged);
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
                        hooks.info("same-name overwrite accepted via generated JNI");
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        Method semantic = AdaptiveDexResolver.resolveSemanticNative(
                runtime, hooks, DUPLICATE_SEMANTIC);
        if (semantic != null) {
            try {
                Class<?>[] p = semantic.getParameterTypes();
                if (p.length == 3 && p[0] == long.class && p[1] == long.class
                        && p[2] == boolean.class) {
                    semantic.invoke(null, ptr, callbackId, true);
                    hooks.info("same-name overwrite accepted via semantic JNI");
                    return true;
                }
            } catch (Throwable t) {
                hooks.warn("same-name overwrite semantic JNI failed: "
                        + t.getClass().getSimpleName());
            }
        }

        if (profile.isVerifiedExact()) {
            try {
                int selector = profile.is152() ? Chrome152.DUPLICATE_ACCEPT : 2;
                Class<?> nativeClass = Reflect.cls(runtime.classLoader, Chrome145.NATIVE);
                Method nativeMethod = Reflect.exact(nativeClass, "VJJZ",
                        int.class, long.class, long.class, boolean.class);
                nativeMethod.invoke(null, selector, ptr, callbackId, true);
                hooks.info("same-name overwrite accepted via verified native fallback");
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
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

    private ReplaceResult replaceSameDirectory(File actual, File desired) {
        File backup = null;
        boolean backedUp = false;
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!isSharedFile(actual) || !isSharedFile(desired)) {
                return ReplaceResult.fail("outside shared storage");
            }
            if (!sameParent(actual, desired)) return ReplaceResult.fail("different directories");
            if (!actual.isFile()) return ReplaceResult.fail("replacement missing");
            if (desired.exists() && !desired.isFile()) return ReplaceResult.fail("original not a file");

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
            if (!desired.isFile() || actual.exists()) {
                if (backedUp && backup != null && backup.exists()) {
                    try {
                        if (desired.exists()) desired.delete();
                        move(backup, desired, true);
                    } catch (Throwable ignored) {}
                }
                return ReplaceResult.fail("verification failed");
            }
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

    private void scheduleResidualCleanup(File oldActual, File desired) {
        if (oldActual == null || desired == null) return;
        final File oldPath;
        final File newPath;
        try {
            oldPath = oldActual.getCanonicalFile();
            newPath = desired.getCanonicalFile();
        } catch (Throwable ignored) { return; }
        if (!sameParent(oldPath, newPath)
                || !DownloadNamePolicy.matchesUniquifiedName(newPath.getName(), oldPath.getName())) {
            return;
        }
        for (long delay : RESIDUAL_DELAYS_MS) {
            main.postDelayed(() -> cleanupResidual(oldPath, newPath, delay), delay);
        }
    }

    private void cleanupResidual(File oldPath, File desired, long delay) {
        try {
            boolean removedFile = false;
            if (oldPath.exists() && oldPath.isFile()) removedFile = oldPath.delete();
            int removedRows = removeMediaStorePath(oldPath);
            refreshMediaIndex(oldPath, desired, "delay=" + delay);
            if (removedFile || removedRows > 0) {
                hooks.info("same-name overwrite residual cleaned: path=" + oldPath.getAbsolutePath()
                        + " file=" + removedFile + " mediaRows=" + removedRows
                        + " delay=" + delay);
            }
        } catch (Throwable t) {
            hooks.warn("same-name overwrite residual cleanup failed: "
                    + t.getClass().getSimpleName() + " delay=" + delay);
        }
    }

    private void refreshMediaIndex(File oldPath, File desired, String phase) {
        try {
            int removedRows = removeMediaStorePath(oldPath);
            ArrayList<String> paths = new ArrayList<>(2);
            if (oldPath != null) paths.add(oldPath.getAbsolutePath());
            if (desired != null) paths.add(desired.getAbsolutePath());
            if (!paths.isEmpty()) {
                MediaScannerConnection.scanFile(runtime.application,
                        paths.toArray(new String[0]), null, null);
            }
            if (removedRows > 0) {
                hooks.info("same-name overwrite media index cleaned: old="
                        + (oldPath == null ? "<none>" : oldPath.getAbsolutePath())
                        + " rows=" + removedRows + " phase=" + phase);
            }
        } catch (Throwable t) {
            hooks.warn("same-name overwrite media refresh failed: "
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
                    out.put(canonical.getPath(),
                            new FileStamp(canonical.length(), canonical.lastModified()));
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
        return base != null && value != null
                && (base.equals(value) || DownloadNamePolicy.matchesUniquifiedName(base, value));
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
        if (pending == null) return;
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

    private DuplicateTarget duplicateTarget(Object[] args) {
        if (args == null) return null;
        String filename = null;
        File hintDir = null;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = (String) arg;
            if (value.startsWith("/")) {
                File file = sharedFile(value);
                if (file != null) {
                    filename = file.getName();
                    hintDir = file.getParentFile();
                    break;
                }
            }
            if (filename == null && AdaptiveDownloadInfo.looksLikeFileName(value)) filename = value;
        }
        filename = DownloadNamePolicy.fileNameOnly(filename);
        if (filename == null || filename.isBlank()) return null;
        return new DuplicateTarget(filename, hintDir);
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        if (profile.isVerifiedExact()) {
            try {
                long value = Reflect.getLong(bridge, "a");
                if (value != 0L) return value;
            } catch (Throwable ignored) {}
        }
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
        try { return found.getLong(bridge); }
        catch (Throwable ignored) { return 0L; }
    }

    private boolean hasMethod(String owner, String name) {
        try { return !Reflect.named(Reflect.cls(runtime.classLoader, owner), name).isEmpty(); }
        catch (Throwable ignored) { return false; }
    }

    private static long lastLong(Object[] args) {
        if (args == null) return 0L;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Number) return ((Number) args[i]).longValue();
        }
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
            String rootPath = root.getPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
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

    private static final class DuplicateTarget {
        final String baseName;
        final File hintDirectory;
        DuplicateTarget(String baseName, File hintDirectory) {
            this.baseName = baseName;
            this.hintDirectory = hintDirectory;
        }
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
        FileStamp(long length, long modified) {
            this.length = length;
            this.modified = modified;
        }
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
