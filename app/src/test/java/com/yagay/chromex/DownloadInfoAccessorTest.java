package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

public class DownloadInfoAccessorTest {
    private static final class ObfuscatedInfo {
        String a = "12345678-1234-1234-1234-123456789abc";
        String c = "application/zip";
        String e = "demo (7).zip";
        String g = "/storage/emulated/0/Download/demo (7).zip";
    }

    @Test
    public void readsObfuscatedInfoBySemanticValueShape() {
        ObfuscatedInfo info = new ObfuscatedInfo();
        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, null);
        assertEquals("application/zip", values.mime);
        assertEquals("/storage/emulated/0/Download/demo (7).zip", values.path);
        assertEquals("demo (7).zip", values.name);
        assertTrue(values.usable());
    }

    @Test
    public void rewritesOnlyPathAndNameSemanticStrings() {
        ObfuscatedInfo info = new ObfuscatedInfo();
        boolean changed = DownloadInfoAccessor.rewrite(info, null,
                new File("/storage/emulated/0/Download/demo.zip"));
        assertTrue(changed);
        assertEquals("application/zip", info.c);
        assertEquals("demo.zip", info.e);
        assertEquals("/storage/emulated/0/Download/demo.zip", info.g);
        assertEquals("12345678-1234-1234-1234-123456789abc", info.a);
    }
}
