package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdaptiveDownloadInfoTest {
    private static final class ObfuscatedInfo {
        String a = "https://example.com/files/report.pdf";
        String b = "550e8400-e29b-41d4-a716-446655440000";
        String c = "application/pdf";
        String d = "/storage/emulated/0/Download/report.pdf";
        String e = "report.pdf";
    }

    private static final class NameOnlyInfo {
        String a = "application/vnd.android.package-archive";
        String b = "release.apk";
        String c = "https://example.com/release.apk";
    }

    @Test
    public void extractsObfuscatedDownloadFieldsByShape() {
        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(new ObfuscatedInfo());
        assertEquals("application/pdf", values.mime);
        assertEquals("/storage/emulated/0/Download/report.pdf", values.path);
        assertEquals("report.pdf", values.name);
        assertTrue(values.usable());
    }

    @Test
    public void acceptsNameWhenForkDoesNotExposePath() {
        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(new NameOnlyInfo());
        assertEquals("application/vnd.android.package-archive", values.mime);
        assertNull(values.path);
        assertEquals("release.apk", values.name);
        assertTrue(values.usable());
    }

    @Test
    public void classifiersRejectUrlAsFilename() {
        assertTrue(AdaptiveDownloadInfo.looksLikeMime("application/pdf"));
        assertTrue(AdaptiveDownloadInfo.looksLikePath("content://downloads/42"));
        assertTrue(AdaptiveDownloadInfo.looksLikeFileName("archive.apks"));
    }
}
