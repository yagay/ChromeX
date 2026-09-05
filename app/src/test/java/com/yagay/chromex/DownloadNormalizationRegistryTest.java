package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;

public class DownloadNormalizationRegistryTest {
    @Test
    public void resolvesExactOldPathToFinalPath() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "chromex-registry-test");
        File oldFile = new File(dir, "app (12).apk");
        File newFile = new File(dir, "app.apk");

        DownloadNormalizationRegistry.register(oldFile, newFile);

        assertEquals(newFile.getCanonicalPath(),
                DownloadNormalizationRegistry.resolve(oldFile.getCanonicalPath()));
        assertEquals(newFile.getCanonicalPath(),
                DownloadNormalizationRegistry.logicalPath(oldFile.getCanonicalPath()));
        assertEquals(newFile.getCanonicalPath(),
                DownloadNormalizationRegistry.logicalPath(newFile.getCanonicalPath()));
    }

    @Test
    public void unrelatedPathIsNotRewritten() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "chromex-registry-test-2");
        File unrelated = new File(dir, "other.apk");
        assertNull(DownloadNormalizationRegistry.resolve(unrelated.getCanonicalPath()));
    }
}
