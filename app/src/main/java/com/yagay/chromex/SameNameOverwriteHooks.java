package com.yagay.chromex;

import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

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

/**
 * Java fallback for Chrome Android same-name overwrite.
 *
 * <p>Chromium's real conflict policy lives in native DownloadPathReservationTracker. The Java
 * duplicate callback exposes only accept/cancel, so a Java-only module cannot select native
 * OVERWRITE. This class therefore performs one narrow fallback: let Chrome finish its download,
 * discover only the file created by that conflict, transactionally replace the original in the
 * same directory, then publish an exact old-path -> new-path mapping for history/UI consumers.</p>
 */
final class SameNameOverwriteHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final int MAX_RESOLVE_RETRIES = 20;
    private static final long RESOLVE_RETRY_MS = 250L;
    private static final Object LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    SameNameOverwriteHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
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
                "chromex:download:overwrite-duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                        hooks.warn("same-name overwrite: duplicate target unavailable");
                        return chain.proceed();
                    }

                    File hint = sharedFile((String) chain.getArg(1));
                    if (hint == null || hint.getName().isBlank()) {
                        hooks.warn("same-name overwrite: duplicate target outside shared storage");
                        return chain.proceed();
                    }
                    long callbackId = lastLong(chain.getArgs().toArray());
                    if (callbackId == 0L) {
                        hooks.warn("same-name overwrite: callback id unavailable");
                        return chain.proceed();
                    }

                    PendingTarget pending = remember(callbackId, hint.getName(), hint.getParentFile());
                    if (!confirmDuplicate(chain.getThisObject(), callbackId)) {
                        forget(pending);
                        hooks.warn("same-name overwrite: callback unresolved; keeping Chrome dialog");
                        return chain.proceed();
                    }
                    hooks.info("same-name overwrite armed: name=" + pending.baseName
                            + " hintDir=" + safePath(pending.hintDirectory)
                            + " callback=" + callbackId);
                    return null;
                });
    }

    private void installCompletionNormalizer() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER);
        } catch (Throwable t) {
            hooks.warn("same-name overwrite: DownloadController unavailable");
            return;
        }

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:download:overwrite-completed", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (Config.get(prefs, Config.OVERWRITE_DUPLICATE) && info != null) {
                        try { normalize(info, 0); }
                        catch (Throwable t) { hooks.error("same-name overwrite completion", t); }
                    }
                    return result;
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

    private void normalize(Object info, int attempt) {
        if (info == null || pendingCount() == 0) return;

        String path = stringAccessor(info, "getFilePath",
                Chrome152.matches(runtime) ? Chrome152.DOWNLOAD_INFO_PATH : null);
        String name = stringAccessor(info, "getFileName",
                Chrome152.matches(runtime) ? Chrome152.DOWNLOAD_INFO_NAME : null);
        String alt = Chrome152.matches(runtime) ? stringField(info, "f") : null;
        File direct = sharedFile(path);

        PendingTarget pending = takeMatching(direct, path, name, alt);
        if (pending == null) {
            if (attempt == 0) hooks.warn("same-name overwrite completion unmatched: path="
                    + safeValue(path) + " name=" + safeValue(name)
                    + " pending=" + pendingCount());
            return;
        }

        File actual = resolveActualFile(pending, direct, path, name, alt);
        if (actual == null) {
            restorePending(pending);
            if (attempt < MAX_RESOLVE_RETRIES) {
                main.postDelayed(() -> normalize(info, attempt + 1), RESOLVE_RETRY_MS);
            } else {
                hooks.warn("same-name overwrite actual file unresolved: name=" + pending.baseName
                        + " reportedPath=" + safeValue(path)
                        + " dirs=" + directorySummary(pending, direct));
            }
            return;
        }

        File desired;
        try { desired = new File(actual.getParentFile(), pending.baseName).getCanonicalFile(); }
        catch (Throwable t) {
            restorePending(pending);
            hooks.warn("same-name overwrite target construction failed: "
                    + t.getClass().getSimpleName());
            return;
        }

        if (!isSharedFile(desired) || !sameParent(actual, desired)) {
            restorePending(pending);
            hooks.warn("same-name overwrite refused non-local target: " + desired);
            return;
        }

        if (sameFile(actual, desired)) {
            updateDownloadInfo(info, desired);
            hooks.info("same-name overwrite already original: " + desired.getAbsolutePath());
            return;
        }

        if (!DownloadNamePolicy.matchesUniquifiedName(pending.baseName, actual.getName())) {
            restorePending(pending);
            hooks.warn("same-name overwrite refused unexpected target: " + actual.getAbsolutePath());
            return;
        }

        File oldActual = actual;
        ReplaceResult replace = transactionalReplaceSameDirectory(actual, desired);
        if (!replace.success) {
            restorePending(pending);
            hooks.warn("same-name overwrite failed: " + actual.getAbsolutePath() + " -> "
                    + desired.getAbsolutePath() + " :: " + replace.detail);
            return;
        }

        DownloadNormalizationRegistry.register(oldActual, desired);
        updateDownloadInfo(info, desired);
        try {
            MediaScannerConnection.scanFile(runtime.application,
                    new String[]{desired.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {}
        hooks.info("same-name overwrite normalized: " + oldActual.getAbsolutePath()
                + " -> " + desired.getAbsolutePath()
                + " via " + replace.detail + " attempt=" + attempt);
    }

    private File resolveActualFile(PendingTarget pending, File direct, String... reported) {
        try {
            if (usableCandidate(pending, direct)) return direct.getCanonicalFile();
            List<File> dirs = downloadDirectories(pending, direct);

            for (String value : reported) {
                String reportedName = DownloadNamePolicy.fileNameOnly(value);
                if (reportedName == null) continue;
                for (File dir : dirs) {
                    File candidate = new File(dir, reportedName);
                    if (usableCandidate(pending, candidate)) return candidate.getCanonicalFile();
                }
            }

            File best = null;
            long bestModified = Long.MIN_VALUE;
            for (File dir : dirs) {
                File[] files;
                try { files = dir.listFiles(); } catch (Throwable ignored) { files = null; }
                if (files == null) continue;
                for (File candidate : files) {
                    if (candidate == null || !candidate.isFile()) continue;
                    String candidateName = candidate.getName();
                    if (!candidateName.equals(pending.baseName)
                            && !DownloadNamePolicy.matchesUniquifiedName(
                            pending.baseName, candidateName)) continue;
                    if (!usableCandidate(pending, candidate)) continue;
                    long modified = candidate.lastModified();
                    if (best == null || modified > bestModified) {
                        best = candidate.getCanonicalFile();
                        bestModified = modified;
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
            candidate = candidate.getCanonicalFile();
            if (!isSharedFile(candidate) || !candidate.exists() || !candidate.isFile()) return false;
            String name = candidate.getName();
            if (!name.equals(pending.baseName)
                    && !DownloadNamePolicy.matchesUniquifiedName(pending.baseName, name)) return false;
            FileStamp before = pending.before.get(candidate.getCanonicalPath());
            return before == null
                    || before.length != candidate.length()
                    || before.modified != candidate.lastModified();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ReplaceResult transactionalReplaceSameDirectory(File actual, File desired) {
        File backup = null;
        boolean backedUp = false;
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!isSharedFile(actual) || !isSharedFile(desired)) {
                return ReplaceResult.fail("path outside shared storage");
            }
            if (!sameParent(actual, desired)) return ReplaceResult.fail("different directories");
            if (!actual.exists() || !actual.isFile()) return ReplaceResult.fail("replacement missing");
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
                        return ReplaceResult.fail("move/rollback failed: "
                                + moveError.getClass().getSimpleName() + "/"
                                + restoreError.getClass().getSimpleName());
                    }
                }
                return ReplaceResult.fail("move failed: " + moveError.getClass().getSimpleName());
            }

            if (!desired.exists() || actual.exists()) {
                if (backedUp && backup != null && backup.exists()) {
                    try {
                        if (desired.exists()) desired.delete();
                        move(backup, desired, true);
                    } catch (Throwable ignored) {}
                }
                return ReplaceResult.fail("replacement verification failed");
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
            return;
        } catch (AtomicMoveNotSupportedException ignored) {}
        if (replace) Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        else Files.move(from.toPath(), to.toPath());
    }

    private void updateDownloadInfo(Object info, File desired) {
        if (info == null || desired == null || !Chrome152.matches(runtime)) return;
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, desired.getPath()); } catch (Throwable ignored) {}
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, desired.getName()); } catch (Throwable ignored) {}
        try { Reflect.set(info, "f", desired.getName()); } catch (Throwable ignored) {}
    }

    private PendingTarget remember(long callbackId, String baseName, File hintDirectory) {
        long now = System.currentTimeMillis();
        PendingTarget pending = new PendingTarget(callbackId, baseName,
                canonicalDirectory(hintDirectory), now, snapshot(baseName, hintDirectory));
        synchronized (LOCK) {
            pruneLocked(now);
            PENDING.removeIf(old -> old.callbackId == callbackId);
            PENDING.add(pending);
            while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
        }
        return pending;
    }

    private Map<String, FileStamp> snapshot(String baseName, File hintDirectory) {
        LinkedHashMap<String, FileStamp> result = new LinkedHashMap<>();
        PendingTarget probe = new PendingTarget(0L, baseName,
                canonicalDirectory(hintDirectory), System.currentTimeMillis(), result);
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
                    result.put(canonical.getPath(),
                            new FileStamp(canonical.length(), canonical.lastModified()));
                } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private List<File> downloadDirectories(PendingTarget pending, File direct) {
        LinkedHashMap<String, File> unique = new LinkedHashMap<>();
        addDirectory(unique, direct == null ? null : direct.getParentFile());
        addDirectory(unique, pending == null ? null : pending.hintDirectory);
        try { addDirectory(unique, runtime.application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        try { addDirectory(unique, new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        return new ArrayList<>(unique.values());
    }

    private static File canonicalDirectory(File directory) {
        if (directory == null) return null;
        try {
            File canonical = directory.getCanonicalFile();
            return isSharedFile(canonical) ? canonical : null;
        } catch (Throwable ignored) { return null; }
    }

    private static void addDirectory(Map<String, File> out, File directory) {
        File canonical = canonicalDirectory(directory);
        if (canonical != null) out.put(canonical.getPath(), canonical);
    }

    private String directorySummary(PendingTarget pending, File direct) {
        StringBuilder out = new StringBuilder();
        for (File dir : downloadDirectories(pending, direct)) {
            if (out.length() > 0) out.append('|');
            out.append(dir.getAbsolutePath());
        }
        return out.toString();
    }

    private void restorePending(PendingTarget pending) {
        if (pending == null) return;
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            PENDING.removeIf(old -> old.callbackId == pending.callbackId);
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

    private PendingTarget takeMatching(File actual, String... reported) {
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            PendingTarget found = null;
            for (PendingTarget pending : PENDING) {
                boolean matches = actual != null && (pending.baseName.equals(actual.getName())
                        || DownloadNamePolicy.matchesUniquifiedName(
                        pending.baseName, actual.getName()));
                if (!matches) {
                    for (String value : reported) {
                        String reportedName = DownloadNamePolicy.fileNameOnly(value);
                        if (pending.baseName.equals(reportedName)
                                || DownloadNamePolicy.matchesUniquifiedName(
                                pending.baseName, reportedName)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
                if (found != null) return null;
                found = pending;
            }
            if (found != null) PENDING.remove(found);
            return found;
        }
    }

    private static void pruneLocked(long now) {
        Iterator<PendingTarget> it = PENDING.iterator();
        while (it.hasNext()) if (now - it.next().time > PENDING_TTL_MS) it.remove();
    }

    private boolean confirmDuplicate(Object bridge, long callbackId) {
        if (Chrome152.matches(runtime)) {
            try {
                long ptr = nativePtr(bridge);
                if (ptr == 0L) return false;
                Class<?> n = Reflect.cls(runtime.classLoader, Chrome145.NATIVE);
                Method method = Reflect.exact(n, "VJJZ",
                        int.class, long.class, long.class, boolean.class);
                method.invoke(null, Chrome152.DUPLICATE_ACCEPT, ptr, callbackId, true);
                return true;
            } catch (Throwable t) {
                hooks.warn("same-name overwrite Chrome 152 callback failed: "
                        + t.getClass().getSimpleName());
                return false;
            }
        }

        try {
            Class<?> jni = Reflect.cls(runtime.classLoader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridgeJni");
            Object instance = Reflect.callStatic(jni, "get");
            long ptr = nativePtr(bridge);
            if (instance == null || ptr == 0L) return false;
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
            long ptr = Reflect.getLong(bridge, "a");
            if (ptr != 0L) return ptr;
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
        try { return found.getLong(bridge); } catch (Throwable ignored) { return 0L; }
    }

    private static String stringAccessor(Object value, String getter, String fallbackField) {
        if (value == null) return null;
        try {
            Object result = Reflect.call(value, getter);
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return fallbackField == null ? null : stringField(value, fallbackField);
    }

    private static String stringField(Object value, String field) {
        try {
            Object result = Reflect.get(value, field);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) { return null; }
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
            String path = file.getCanonicalPath();
            return path.startsWith(root.getPath() + File.separator);
        } catch (Throwable ignored) { return false; }
    }

    private static boolean sameFile(File a, File b) {
        try { return a != null && b != null && a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean sameParent(File a, File b) {
        try {
            File ap = a == null ? null : a.getCanonicalFile().getParentFile();
            File bp = b == null ? null : b.getCanonicalFile().getParentFile();
            return ap != null && bp != null && ap.equals(bp);
        } catch (Throwable ignored) { return false; }
    }

    private static long lastLong(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) if (args[i] instanceof Long) return (Long) args[i];
        return 0L;
    }

    private static String safeValue(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String safePath(File value) {
        return value == null ? "<none>" : value.getAbsolutePath();
    }

    private static final class PendingTarget {
        final long callbackId;
        final String baseName;
        final File hintDirectory;
        final long time;
        final Map<String, FileStamp> before;

        PendingTarget(long callbackId, String baseName, File hintDirectory,
                      long time, Map<String, FileStamp> before) {
            this.callbackId = callbackId;
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
