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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal same-name overwrite engine. Chromium's own OfflineContent rename is authoritative;
 * filesystem replacement is retained only as a compatibility fallback.
 */
final class NativeFirstSameNameOverwriteHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String DUPLICATE_SEMANTIC =
            "org_chromium_chrome_browser_download_DuplicateDownloadDialogBridge_onConfirmed";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final int MAX_FILE_RETRIES = 24;
    private static final int MAX_NATIVE_WAIT_RETRIES = 8;
    private static final long RETRY_MS = 250L;
    private static final long[] RESIDUAL_DELAYS = {300L, 1000L, 2500L};
    private static final Object LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final OfflineContentRenameBinding renameBinding;
    private final Handler main = new Handler(Looper.getMainLooper());

    NativeFirstSameNameOverwriteHooks(ChromiumProfile profile, ChromeRuntime runtime,
                                      HookSupport hooks, SharedPreferences prefs,
                                      OfflineContentRenameBinding renameBinding) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
        this.renameBinding = renameBinding;
    }

    void install() {
        installDuplicateCapture();
        installCompletion(Chrome145.DOWNLOAD_CONTROLLER,
                "chromex:overwrite:source-first:controller");
        installCompletion(Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "chromex:overwrite:source-first:manager");
        hooks.info("source-first same-name overwrite installed; nativeBackend="
                + (renameBinding == null ? "none" : renameBinding.backendLabel()));
    }

    private void installDuplicateCapture() {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, DUPLICATE_BRIDGE);
            if (Reflect.named(type, "showDialog").isEmpty()) return;
        } catch (Throwable ignored) { return; }
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE, "showDialog",
                "chromex:overwrite:source-first:duplicate", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) return chain.proceed();
                    DuplicateTarget target = duplicateTarget(chain.getArgs().toArray());
                    long callback = lastLong(chain.getArgs().toArray());
                    if (target == null || callback == 0L) {
                        hooks.warn("same-name overwrite: duplicate target/callback unavailable");
                        return chain.proceed();
                    }
                    PendingTarget pending = remember(callback, target.baseName, target.directory);
                    if (!confirmDuplicate(chain.getThisObject(), callback)) {
                        forget(pending);
                        hooks.warn("same-name overwrite: duplicate callback unresolved; preserving dialog");
                        return chain.proceed();
                    }
                    hooks.info("same-name overwrite armed: name=" + pending.baseName
                            + " dir=" + safePath(pending.directory));
                    return null;
                });
    }

    private void installCompletion(String owner, String id) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, owner);
            if (Reflect.named(type, "onDownloadCompleted").isEmpty()) return;
        } catch (Throwable ignored) { return; }
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
            if (attempt == 0) hooks.warn("same-name overwrite completion unmatched: path="
                    + safeValue(values.path) + " name=" + safeValue(values.name)
                    + " pending=" + pendingCount());
            return;
        }

        File actual = resolveActualFile(pending, direct, values.path, values.name);
        if (actual == null) {
            restorePending(pending);
            if (attempt < MAX_FILE_RETRIES) {
                main.postDelayed(() -> normalize(info, attempt + 1), RETRY_MS);
            } else {
                hooks.warn("same-name overwrite actual file unresolved: " + pending.baseName);
            }
            return;
        }

        final File desired;
        try { desired = new File(actual.getParentFile(), pending.baseName).getCanonicalFile(); }
        catch (Throwable t) { restorePending(pending); return; }
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
            hooks.warn("same-name overwrite refused unexpected numbered name: " + actual.getName());
            return;
        }

        if (renameBinding != null && renameBinding.available()) {
            NativePreparation prep = prepareNativeRename(desired);
            if (prep != null) {
                boolean started = renameBinding.rename(actual.getAbsolutePath(), actual.getName(),
                        desired.getName(), (success, code, source) -> main.post(() -> {
                            if (success) {
                                finishNativeSuccess(info, actual, desired, prep, source, code);
                            } else {
                                rollbackPreparation(prep, desired);
                                hooks.warn("Chromium source rename rejected: " + actual.getName()
                                        + " -> " + desired.getName() + " result=" + code
                                        + " source=" + source + "; using filesystem fallback");
                                fallbackReplace(info, actual, desired, "native-result=" + code);
                            }
                        }));
                if (started) return;
                rollbackPreparation(prep, desired);
                if (attempt < MAX_NATIVE_WAIT_RETRIES) {
                    restorePending(pending);
                    main.postDelayed(() -> normalize(info, attempt + 1), RETRY_MS);
                    return;
                }
            }
        }

        fallbackReplace(info, actual, desired, "native-unavailable");
    }

    /** Move only the old original away, leaving the numbered Chromium download untouched. */
    private NativePreparation prepareNativeRename(File desired) {
        try {
            if (!desired.exists()) return new NativePreparation(null);
            if (!desired.isFile()) return null;
            File backup = new File(desired.getParentFile(),
                    "." + desired.getName() + ".chromex-native-backup-" + System.nanoTime());
            move(desired, backup, false);
            return new NativePreparation(backup);
        } catch (Throwable t) {
            hooks.warn("same-name overwrite native preparation failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void finishNativeSuccess(Object info, File oldActual, File desired,
                                     NativePreparation prep, String source, int result) {
        main.postDelayed(() -> {
            try {
                if (!desired.isFile()) {
                    rollbackPreparation(prep, desired);
                    hooks.warn("Chromium source rename reported success but target is missing; fallback");
                    fallbackReplace(info, oldActual, desired, "native-verification");
                    return;
                }
                if (prep.backup != null && prep.backup.exists() && !prep.backup.delete()) {
                    prep.backup.deleteOnExit();
                }
                DownloadNormalizationRegistry.register(oldActual, desired);
                DownloadInfoAccessor.rewrite(info, profile, desired);
                refreshMediaIndex(oldActual, desired, "native-source");
                scheduleResidualCleanup(oldActual, desired);
                hooks.info("same-name overwrite source normalized: "
                        + oldActual.getAbsolutePath() + " -> " + desired.getAbsolutePath()
                        + " source=" + source + " result=" + result);
            } catch (Throwable t) {
                hooks.warn("same-name overwrite native completion failed: "
                        + t.getClass().getSimpleName());
            }
        }, 80L);
    }

    private void fallbackReplace(Object info, File actual, File desired, String reason) {
        ReplaceResult replace = replaceSameDirectory(actual, desired);
        if (!replace.success) {
            hooks.warn("same-name overwrite fallback failed: " + actual + " -> " + desired
                    + " :: " + replace.detail + " reason=" + reason);
            return;
        }
        DownloadNormalizationRegistry.register(actual, desired);
        DownloadInfoAccessor.rewrite(info, profile, desired);
        refreshMediaIndex(actual, desired, "filesystem-fallback");
        scheduleResidualCleanup(actual, desired);
        hooks.info("same-name overwrite fallback normalized: " + actual.getAbsolutePath()
                + " -> " + desired.getAbsolutePath() + " via " + replace.detail
                + " reason=" + reason);
    }

    private void rollbackPreparation(NativePreparation prep, File desired) {
        if (prep == null || prep.backup == null || !prep.backup.exists()) return;
        try {
            if (desired.exists()) desired.delete();
            move(prep.backup, desired, true);
        } catch (Throwable t) {
            hooks.warn("same-name overwrite backup rollback failed: " + t.getClass().getSimpleName());
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
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        Method semantic = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks, DUPLICATE_SEMANTIC);
        if (semantic != null) {
            try {
                Class<?>[] p = semantic.getParameterTypes();
                if (p.length == 3 && p[0] == long.class && p[1] == long.class
                        && p[2] == boolean.class) {
                    semantic.invoke(null, ptr, callbackId, true);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        if (profile.isVerifiedExact()) {
            try {
                int selector = profile.is152() ? Chrome152.DUPLICATE_ACCEPT : 2;
                Method method = Reflect.exact(Reflect.cls(runtime.classLoader, Chrome145.NATIVE),
                        "VJJZ", int.class, long.class, long.class, boolean.class);
                method.invoke(null, selector, ptr, callbackId, true);
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
                String name = DownloadNamePolicy.fileNameOnly(raw);
                if (name == null) continue;
                for (File dir : dirs) {
                    File candidate = new File(dir, name);
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
        } catch (Throwable ignored) { return null; }
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
        } catch (Throwable ignored) { return false; }
    }

    private ReplaceResult replaceSameDirectory(File actual, File desired) {
        File backup = null;
        boolean backedUp = false;
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!isSharedFile(actual) || !isSharedFile(desired) || !sameParent(actual, desired)) {
                return ReplaceResult.fail("unsafe path");
            }
            if (!actual.isFile()) return ReplaceResult.fail("replacement missing");
            if (desired.exists() && !desired.isFile()) return ReplaceResult.fail("original not file");
            if (desired.exists()) {
                backup = new File(desired.getParentFile(),
                        "." + desired.getName() + ".chromex-backup-" + System.nanoTime());
                move(desired, backup, false);
                backedUp = true;
            }
            try { move(actual, desired, true); }
            catch (Throwable moveError) {
                if (backedUp && backup != null && backup.exists()) {
                    try { if (desired.exists()) desired.delete(); move(backup, desired, true); }
                    catch (Throwable ignored) {}
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
            if (replace) Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from.toPath(), to.toPath());
        }
    }

    private void scheduleResidualCleanup(File oldPath, File desired) {
        if (oldPath == null || desired == null || !sameParent(oldPath, desired)) return;
        if (!DownloadNamePolicy.matchesUniquifiedName(desired.getName(), oldPath.getName())) return;
        for (long delay : RESIDUAL_DELAYS) main.postDelayed(() -> {
            try {
                if (oldPath.exists() && oldPath.isFile()) oldPath.delete();
                removeMediaStorePath(oldPath);
                refreshMediaIndex(oldPath, desired, "delay=" + delay);
            } catch (Throwable ignored) {}
        }, delay);
    }

    private void refreshMediaIndex(File oldPath, File desired, String phase) {
        try {
            removeMediaStorePath(oldPath);
            ArrayList<String> paths = new ArrayList<>(2);
            if (oldPath != null) paths.add(oldPath.getAbsolutePath());
            if (desired != null) paths.add(desired.getAbsolutePath());
            if (!paths.isEmpty()) MediaScannerConnection.scanFile(runtime.application,
                    paths.toArray(new String[0]), null, null);
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
                    MediaStore.MediaColumns.DATA + "=?", new String[]{oldPath.getAbsolutePath()});
        } catch (Throwable ignored) { return 0; }
    }

    private PendingTarget remember(long callback, String baseName, File directory) {
        long now = System.currentTimeMillis();
        PendingTarget pending = new PendingTarget(callback, baseName, canonicalDirectory(directory),
                now, snapshot(baseName, directory));
        synchronized (LOCK) {
            pruneLocked(now);
            PENDING.removeIf(old -> old.callback == callback);
            PENDING.add(pending);
            while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
        }
        return pending;
    }

    private Map<String, FileStamp> snapshot(String baseName, File hint) {
        LinkedHashMap<String, FileStamp> out = new LinkedHashMap<>();
        PendingTarget probe = new PendingTarget(0L, baseName, canonicalDirectory(hint),
                System.currentTimeMillis(), out);
        for (File dir : downloadDirectories(probe, null)) {
            File[] files;
            try { files = dir.listFiles(); } catch (Throwable ignored) { files = null; }
            if (files == null) continue;
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                if (!file.getName().equals(baseName)
                        && !DownloadNamePolicy.matchesUniquifiedName(baseName, file.getName())) continue;
                try {
                    File c = file.getCanonicalFile();
                    out.put(c.getPath(), new FileStamp(c.length(), c.lastModified()));
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private PendingTarget takeMatching(File direct, String... reported) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            pruneLocked(now);
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                PendingTarget pending = PENDING.get(i);
                if (matches(pending, direct, reported)) {
                    PENDING.remove(i);
                    return pending;
                }
            }
        }
        return null;
    }

    private boolean matches(PendingTarget pending, File direct, String... reported) {
        if (pending == null) return false;
        if (direct != null && DownloadNamePolicy.matchesUniquifiedName(
                pending.baseName, direct.getName())) return true;
        if (reported != null) {
            for (String raw : reported) {
                String name = DownloadNamePolicy.fileNameOnly(raw);
                if (name != null && (name.equals(pending.baseName)
                        || DownloadNamePolicy.matchesUniquifiedName(pending.baseName, name))) return true;
            }
        }
        return false;
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
        synchronized (LOCK) { pruneLocked(System.currentTimeMillis()); return PENDING.size(); }
    }

    private static void pruneLocked(long now) {
        PENDING.removeIf(p -> now - p.created > PENDING_TTL_MS);
    }

    private List<File> downloadDirectories(PendingTarget pending, File direct) {
        LinkedHashMap<String, File> dirs = new LinkedHashMap<>();
        addDir(dirs, direct == null ? null : direct.getParentFile());
        addDir(dirs, pending == null ? null : pending.directory);
        try { addDir(dirs, runtime.application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        try { addDir(dirs, new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)); }
        catch (Throwable ignored) {}
        return new ArrayList<>(dirs.values());
    }

    private static void addDir(Map<String, File> out, File dir) {
        File c = canonicalDirectory(dir);
        if (c != null) out.put(c.getPath(), c);
    }

    private static File canonicalDirectory(File dir) {
        if (dir == null) return null;
        try {
            File c = dir.getCanonicalFile();
            return isSharedFile(c) ? c : null;
        } catch (Throwable ignored) { return null; }
    }

    private DuplicateTarget duplicateTarget(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            File file = sharedFile((String) arg);
            if (file != null && file.getName() != null && !file.getName().isBlank()) {
                return new DuplicateTarget(file.getName(), file.getParentFile());
            }
        }
        return null;
    }

    private static File sharedFile(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.startsWith("file://") ? raw.substring(7) : raw;
        if (!value.startsWith("/")) return null;
        try {
            File file = new File(value).getCanonicalFile();
            return isSharedFile(file) ? file : null;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean isSharedFile(File file) {
        if (file == null) return false;
        String path = file.getAbsolutePath();
        return path.startsWith("/storage/") || path.startsWith("/sdcard/")
                || path.startsWith("/mnt/media_rw/");
    }

    private static boolean sameParent(File a, File b) {
        try { return a.getCanonicalFile().getParentFile().equals(b.getCanonicalFile().getParentFile()); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean sameFile(File a, File b) {
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (Throwable ignored) { return false; }
    }

    private static long lastLong(Object[] args) {
        if (args == null) return 0L;
        for (int i = args.length - 1; i >= 0; i--) if (args[i] instanceof Long) return (Long) args[i];
        return 0L;
    }

    private static long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
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
        try { return found == null ? 0L : found.getLong(bridge); }
        catch (Throwable ignored) { return 0L; }
    }

    private static String safeValue(String raw) {
        String value = DownloadNamePolicy.fileNameOnly(raw);
        return value == null ? "<none>" : value;
    }

    private static String safePath(File file) {
        return file == null ? "<none>" : file.getAbsolutePath();
    }

    private static final class DuplicateTarget {
        final String baseName; final File directory;
        DuplicateTarget(String baseName, File directory) { this.baseName = baseName; this.directory = directory; }
    }

    private static final class PendingTarget {
        final long callback; final String baseName; final File directory; final long created;
        final Map<String, FileStamp> before;
        PendingTarget(long callback, String baseName, File directory, long created,
                      Map<String, FileStamp> before) {
            this.callback = callback; this.baseName = baseName; this.directory = directory;
            this.created = created; this.before = before;
        }
    }

    private static final class FileStamp {
        final long length, modified;
        FileStamp(long length, long modified) { this.length = length; this.modified = modified; }
    }

    private static final class NativePreparation {
        final File backup;
        NativePreparation(File backup) { this.backup = backup; }
    }

    private static final class ReplaceResult {
        final boolean success; final String detail;
        private ReplaceResult(boolean success, String detail) { this.success = success; this.detail = detail; }
        static ReplaceResult ok(String detail) { return new ReplaceResult(true, detail); }
        static ReplaceResult fail(String detail) { return new ReplaceResult(false, detail); }
    }
}
