package com.yagay.chromex;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Transactional same-name overwrite with conservative matching. */
final class SameNameOverwriteHooks {
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final long PENDING_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_PENDING = 64;
    private static final Object LOCK = new Object();
    private static final List<PendingTarget> PENDING = new ArrayList<>();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

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
                    File desired = sharedFile((String) chain.getArg(1));
                    if (desired == null) {
                        hooks.warn("same-name overwrite: target is outside shared storage");
                        return chain.proceed();
                    }
                    long callbackId = lastLong(chain.getArgs().toArray());
                    if (callbackId == 0L) {
                        hooks.warn("same-name overwrite: callback id unavailable");
                        return chain.proceed();
                    }
                    PendingTarget pending = remember(callbackId, desired);
                    if (!confirmDuplicate(chain.getThisObject(), callbackId)) {
                        forget(pending);
                        hooks.warn("same-name overwrite: callback unresolved; keeping Chrome dialog");
                        return chain.proceed();
                    }
                    hooks.info("same-name overwrite armed: " + desired.getName()
                            + " callback=" + callbackId);
                    return null;
                });
    }

    private void installCompletionNormalizer() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER);
        } catch (Throwable t) {
            hooks.warn("same-name overwrite: DownloadController unavailable; completion normalization disabled");
            return;
        }
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:download:overwrite-completed", chain -> {
                    if (Config.get(prefs, Config.OVERWRITE_DUPLICATE)) {
                        try {
                            Object info = findDownloadInfo(chain.getArgs().toArray());
                            normalize(info);
                        } catch (Throwable t) {
                            hooks.error("same-name overwrite completion", t);
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

    private void normalize(Object info) {
        if (info == null || pendingCount() == 0) return;
        String path = stringAccessor(info, "getFilePath",
                Chrome152.matches(runtime) ? Chrome152.DOWNLOAD_INFO_PATH : null);
        String name = stringAccessor(info, "getFileName",
                Chrome152.matches(runtime) ? Chrome152.DOWNLOAD_INFO_NAME : null);
        String alt = Chrome152.matches(runtime) ? stringField(info, "f") : null;

        File direct = sharedFile(path);
        PendingTarget pending = takeMatching(direct, path, name, alt);
        if (pending == null) {
            hooks.warn("same-name overwrite completion unmatched: path=" + safeName(path)
                    + " name=" + safeName(name) + " pending=" + pendingCount());
            return;
        }

        File desired = pending.desired;
        File actual = resolveActualFile(desired, direct, path, name, alt);
        if (actual == null) {
            restorePending(pending);
            hooks.warn("same-name overwrite actual file unresolved: desired=" + desired.getName());
            return;
        }
        if (sameFile(actual, desired)) {
            updateDownloadInfo(info, desired);
            hooks.info("same-name overwrite completed with original name: " + desired.getName());
            return;
        }
        if (!sameParent(actual, desired)
                || !DownloadNamePolicy.matchesUniquifiedName(desired.getName(), actual.getName())) {
            restorePending(pending);
            hooks.warn("same-name overwrite refused unexpected target: " + actual.getName());
            return;
        }

        ReplaceResult result = transactionalReplace(actual, desired);
        if (!result.success) {
            restorePending(pending);
            hooks.warn("same-name overwrite failed: " + actual.getName() + " -> "
                    + desired.getName() + " :: " + result.detail);
            return;
        }
        updateDownloadInfo(info, desired);
        try {
            MediaScannerConnection.scanFile(runtime.application,
                    new String[]{desired.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {}
        hooks.info("same-name overwrite normalized: " + actual.getName()
                + " -> " + desired.getName() + " via " + result.detail);
    }

    private File resolveActualFile(File desired, File direct, String... reported) {
        try {
            if (direct != null && direct.exists() && direct.isFile() && sameParent(direct, desired)) {
                return direct;
            }
            File parent = desired.getParentFile();
            if (parent == null) return null;
            File found = null;
            for (String value : reported) {
                String name = DownloadNamePolicy.fileNameOnly(value);
                if (name == null || !DownloadNamePolicy.matchesUniquifiedName(desired.getName(), name)) {
                    continue;
                }
                File candidate = new File(parent, name).getCanonicalFile();
                if (!isSharedFile(candidate) || !candidate.exists() || !candidate.isFile()) continue;
                if (found != null && !sameFile(found, candidate)) return null;
                found = candidate;
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ReplaceResult transactionalReplace(File actual, File desired) {
        File backup = null;
        boolean backedUp = false;
        try {
            actual = actual.getCanonicalFile();
            desired = desired.getCanonicalFile();
            if (!sameParent(actual, desired)) return ReplaceResult.fail("different directories");
            if (!actual.exists() || !actual.isFile()) return ReplaceResult.fail("replacement missing");
            if (desired.exists() && !desired.isFile()) return ReplaceResult.fail("original is not a file");

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
                        return ReplaceResult.fail("move failed and rollback failed: "
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
            if (backup != null && backup.exists() && !backup.delete()) {
                backup.deleteOnExit();
            }
            return ReplaceResult.ok("transactional filesystem");
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
        } catch (AtomicMoveNotSupportedException ignored) {
        }
        if (replace) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(from.toPath(), to.toPath());
        }
    }

    private void updateDownloadInfo(Object info, File desired) {
        if (info == null || desired == null || !Chrome152.matches(runtime)) return;
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, desired.getPath()); } catch (Throwable ignored) {}
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, desired.getName()); } catch (Throwable ignored) {}
        try { Reflect.set(info, "f", desired.getName()); } catch (Throwable ignored) {}
    }

    private PendingTarget remember(long callbackId, File desired) {
        PendingTarget pending = new PendingTarget(callbackId, desired, System.currentTimeMillis());
        synchronized (LOCK) {
            pruneLocked(pending.time);
            PENDING.removeIf(old -> old.callbackId == callbackId);
            PENDING.add(pending);
            while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
        }
        return pending;
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
                boolean matches = actual != null && sameParent(actual, pending.desired)
                        && DownloadNamePolicy.matchesUniquifiedName(
                        pending.desired.getName(), actual.getName());
                if (!matches) {
                    for (String value : reported) {
                        String name = DownloadNamePolicy.fileNameOnly(value);
                        if (DownloadNamePolicy.matchesUniquifiedName(pending.desired.getName(), name)) {
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
        while (it.hasNext()) {
            if (now - it.next().time > PENDING_TTL_MS) it.remove();
        }
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
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
        }
        return 0L;
    }

    private static String safeName(String value) {
        String name = DownloadNamePolicy.fileNameOnly(value);
        return name == null || name.isBlank() ? "<none>" : name;
    }

    private static final class PendingTarget {
        final long callbackId;
        final File desired;
        final long time;

        PendingTarget(long callbackId, File desired, long time) {
            this.callbackId = callbackId;
            this.desired = desired;
            this.time = time;
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
