package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** Regression fixtures model semantic layouts seen across stock Chromium and vendor forks. */
public class DownloadInfoAccessorTest {
    private static final class StockChromiumStyleInfo {
        String a = "https://example.com/releases/ChromeX-debug.zip";
        String b = "7e57d004-2b97-0e7a-b45f-5387367791cd";
        String c = "application/zip";
        String d = "https://example.com/";
        String e = "ChromeX-debug.zip";
        String f = "ChromeX-debug.zip";
        String g = "/storage/emulated/0/Download/ChromeX-debug.zip";
    }

    private static final class VendorForkStyleInfo {
        String x = "application/vnd.android.package-archive";
        String y = "/storage/emulated/0/Download/release (12).apk";
        String z = "release (12).apk";
    }

    @Test
    public void readsStockChromiumLayoutWithoutVersionProfile() {
        DownloadInfoAccessor.Values values =
                DownloadInfoAccessor.read(new StockChromiumStyleInfo(), null);
        assertEquals("application/zip", values.mime);
        assertEquals("/storage/emulated/0/Download/ChromeX-debug.zip", values.path);
        assertEquals("ChromeX-debug.zip", values.name);
        assertTrue(values.usable());
    }

    @Test
    public void readsVendorLayoutByValueShape() {
        DownloadInfoAccessor.Values values =
                DownloadInfoAccessor.read(new VendorForkStyleInfo(), null);
        assertEquals("application/vnd.android.package-archive", values.mime);
        assertEquals("/storage/emulated/0/Download/release (12).apk", values.path);
        assertEquals("release (12).apk", values.name);
        assertTrue(values.usable());
    }

    @Test
    public void rewriteDoesNotNeedObfuscatedFieldNames() {
        VendorForkStyleInfo info = new VendorForkStyleInfo();
        File target = new File("/storage/emulated/0/Download/release.apk");
        assertTrue(DownloadInfoAccessor.rewrite(info, null, target));
        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, null);
        assertEquals("/storage/emulated/0/Download/release.apk", values.path);
        assertEquals("release.apk", values.name);
        assertEquals("application/vnd.android.package-archive", values.mime);
    }
}
