package com.yagay.chromex;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes Chromium's own UNIQUIFY reservation behave like overwrite without native code patches.
 *
 * <p>Android Chromium confirms a duplicate and then asks DownloadPathReservationTracker for the
 * same path with UNIQUIFY. If the old target is atomically moved aside before that confirmation,
 * Chromium reserves the original name itself instead of creating "name (1).ext". The old file is
 * kept as a same-directory transaction backup until the new download completes.</p>
 */
final class DownloadConflictPolicyBinding {
    private static final String MARKER = ".chromex-reservation-backup-";
    private final Object lock = new Object();
    private final Set<String> activeBackups = new HashSet<>();

    static final class Reservation {
        final File target;
        final File backup;
        private boolean active;

        Reservation(File target, File backup) {
            this.target = target;
            this.backup = backup;
            this.active = true;
        }

        boolean hasBackup() {
            return backup != null;
        }

        boolean isActive() {
            return active;
        }
    }

    /**
     * Atomically vacates {@code target}. A non-existing target is already safe and returns an
     * active no-backup reservation. Null means the target could not be safely prepared.
     */
    Reservation vacate(File target) {
        if (target == null) return null;
        try {
            File canonical = target.getCanonicalFile();
            File parent = canonical.getParentFile();
            if (parent == null || !parent.isDirectory()) return null;
            recoverDirectory(parent);
            if (!canonical.exists()) return new Reservation(canonical, null);
            if (!canonical.isFile()) return null;

            File backup = uniqueBackup(canonical);
            move(canonical, backup, false);
            Reservation reservation = new Reservation(canonical, backup);
            synchronized (lock) { activeBackups.add(backup.getCanonicalPath()); }
            return reservation;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Commit replacement: the newly downloaded target wins and the old backup is removed. */
    boolean commit(Reservation reservation) {
        if (reservation == null || !reservation.active) return true;
        boolean ok = true;
        try {
            if (reservation.backup != null && reservation.backup.exists()) {
                Files.deleteIfExists(reservation.backup.toPath());
                ok = !reservation.backup.exists();
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        if (ok) finish(reservation);
        return ok;
    }

    /**
     * Roll back only when the original target path is still free. Never overwrite a file that may
     * already be a completed/new Chromium download.
     */
    boolean rollback(Reservation reservation) {
        if (reservation == null || !reservation.active) return true;
        try {
            if (reservation.backup == null || !reservation.backup.exists()) {
                finish(reservation);
                return true;
            }
            if (reservation.target.exists()) return false;
            move(reservation.backup, reservation.target, false);
            finish(reservation);
            return reservation.target.isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Recover backups left by a previous browser-process crash. Active transactions in this
     * process are skipped. If a new target exists the backup is stale and can be discarded;
     * otherwise the old original is restored.
     */
    int recoverDirectory(File directory) {
        if (directory == null) return 0;
        int recovered = 0;
        try {
            File dir = directory.getCanonicalFile();
            if (!dir.isDirectory()) return 0;
            File[] files = dir.listFiles();
            if (files == null) return 0;
            for (File backup : files) {
                if (backup == null || !backup.isFile()) continue;
                String name = backup.getName();
                int marker = name.lastIndexOf(MARKER);
                if (!name.startsWith(".") || marker <= 1) continue;
                String path;
                try { path = backup.getCanonicalPath(); }
                catch (Throwable ignored) { continue; }
                synchronized (lock) {
                    if (activeBackups.contains(path)) continue;
                }
                String originalName = name.substring(1, marker);
                if (originalName.isBlank()) continue;
                File target = new File(dir, originalName).getCanonicalFile();
                if (!target.getParentFile().equals(dir)) continue;
                try {
                    if (target.exists()) {
                        Files.deleteIfExists(backup.toPath());
                    } else {
                        move(backup, target, false);
                    }
                    recovered++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return recovered;
    }

    private File uniqueBackup(File target) throws Exception {
        File parent = target.getParentFile();
        String prefix = "." + target.getName() + MARKER;
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            File candidate = new File(parent, prefix + stamp + (i == 0 ? "" : "-" + i));
            if (!candidate.exists()) return candidate.getCanonicalFile();
        }
        throw new IllegalStateException("backup name exhausted");
    }

    private void finish(Reservation reservation) {
        reservation.active = false;
        if (reservation.backup == null) return;
        try {
            synchronized (lock) { activeBackups.remove(reservation.backup.getCanonicalPath()); }
        } catch (Throwable ignored) {}
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
}
