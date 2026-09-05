package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DownloadConflictPolicyBindingTest {
    @Test
    public void vacateAndRollbackRestoresOldOriginal() throws Exception {
        File dir = Files.createTempDirectory("chromex-reservation-rollback").toFile();
        File target = new File(dir, "archive.zip");
        Files.writeString(target.toPath(), "old", StandardCharsets.UTF_8);

        DownloadConflictPolicyBinding binding = new DownloadConflictPolicyBinding();
        DownloadConflictPolicyBinding.Reservation reservation = binding.vacate(target);

        assertNotNull(reservation);
        assertTrue(reservation.hasBackup());
        assertFalse(target.exists());
        assertTrue(reservation.backup.isFile());

        assertTrue(binding.rollback(reservation));
        assertTrue(target.isFile());
        assertEquals("old", Files.readString(target.toPath(), StandardCharsets.UTF_8));
        assertFalse(reservation.backup.exists());
    }

    @Test
    public void commitKeepsNewChromiumTargetAndDeletesOldBackup() throws Exception {
        File dir = Files.createTempDirectory("chromex-reservation-commit").toFile();
        File target = new File(dir, "archive.zip");
        Files.writeString(target.toPath(), "old", StandardCharsets.UTF_8);

        DownloadConflictPolicyBinding binding = new DownloadConflictPolicyBinding();
        DownloadConflictPolicyBinding.Reservation reservation = binding.vacate(target);
        Files.writeString(target.toPath(), "new", StandardCharsets.UTF_8);

        assertTrue(binding.commit(reservation));
        assertTrue(target.isFile());
        assertEquals("new", Files.readString(target.toPath(), StandardCharsets.UTF_8));
        assertFalse(reservation.backup.exists());
    }

    @Test
    public void rollbackNeverOverwritesANewTarget() throws Exception {
        File dir = Files.createTempDirectory("chromex-reservation-safe").toFile();
        File target = new File(dir, "archive.zip");
        Files.writeString(target.toPath(), "old", StandardCharsets.UTF_8);

        DownloadConflictPolicyBinding binding = new DownloadConflictPolicyBinding();
        DownloadConflictPolicyBinding.Reservation reservation = binding.vacate(target);
        Files.writeString(target.toPath(), "new", StandardCharsets.UTF_8);

        assertFalse(binding.rollback(reservation));
        assertEquals("new", Files.readString(target.toPath(), StandardCharsets.UTF_8));
        assertTrue(reservation.backup.isFile());
    }

    @Test
    public void coldRecoveryRestoresBackupWhenTargetIsMissing() throws Exception {
        File dir = Files.createTempDirectory("chromex-reservation-recovery").toFile();
        File target = new File(dir, "archive.zip");
        Files.writeString(target.toPath(), "old", StandardCharsets.UTF_8);

        DownloadConflictPolicyBinding firstProcess = new DownloadConflictPolicyBinding();
        DownloadConflictPolicyBinding.Reservation reservation = firstProcess.vacate(target);
        assertFalse(target.exists());
        assertTrue(reservation.backup.exists());

        // Simulate a new browser process: it has no knowledge of the old active reservation.
        DownloadConflictPolicyBinding nextProcess = new DownloadConflictPolicyBinding();
        assertEquals(1, nextProcess.recoverDirectory(dir));
        assertTrue(target.isFile());
        assertEquals("old", Files.readString(target.toPath(), StandardCharsets.UTF_8));
        assertFalse(reservation.backup.exists());
    }
}
