package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OfflineItemAccessorTest {
    @Test
    public void readsStableOfflineItemFields() {
        FakeOffline item = new FakeOffline();
        item.title = "ChromeX-debug.apk";
        item.filePath = "/storage/emulated/0/Download/ChromeX-debug.apk";
        item.mimeType = "application/vnd.android.package-archive";
        item.id = new FakeContentId("download", "guid-1");

        OfflineItemAccessor.Values values = OfflineItemAccessor.read(item);

        assertEquals(item.title, values.name);
        assertEquals(item.filePath, values.path);
        assertEquals(item.mimeType, values.mime);
        assertSame(item.id, values.contentId);
        assertEquals("download:guid-1", values.contentKey);
        assertTrue(values.usable());
    }

    @Test
    public void readsR8ShapedStringsWithoutFieldNames() {
        ObfuscatedOffline item = new ObfuscatedOffline();
        item.a = "/storage/emulated/0/Download/archive.zip";
        item.b = "archive.zip";
        item.c = "application/zip";

        OfflineItemAccessor.Values values = OfflineItemAccessor.read(item);

        assertEquals(item.a, values.path);
        assertEquals(item.b, values.name);
        assertEquals(item.c, values.mime);
        assertTrue(values.usable());
    }

    static final class FakeContentId {
        String namespace;
        String id;
        FakeContentId(String namespace, String id) {
            this.namespace = namespace;
            this.id = id;
        }
    }

    static final class FakeOffline {
        FakeContentId id;
        String title;
        String filePath;
        String mimeType;
    }

    static final class ObfuscatedOffline {
        String a;
        String b;
        String c;
    }
}
