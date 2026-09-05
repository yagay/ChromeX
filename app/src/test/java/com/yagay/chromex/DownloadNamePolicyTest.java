package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadNamePolicyTest {
    @Test
    public void acceptsChromiumUniquifiedNames() {
        assertTrue(DownloadNamePolicy.matchesUniquifiedName("app.apk", "app.apk"));
        assertTrue(DownloadNamePolicy.matchesUniquifiedName("app.apk", "app (1).apk"));
        assertTrue(DownloadNamePolicy.matchesUniquifiedName("archive.tar.gz", "archive.tar (12).gz"));
        assertTrue(DownloadNamePolicy.matchesUniquifiedName("README", "README (7)"));
    }

    @Test
    public void rejectsLookalikesAndDifferentFiles() {
        assertFalse(DownloadNamePolicy.matchesUniquifiedName("app.apk", "app(1).apk"));
        assertFalse(DownloadNamePolicy.matchesUniquifiedName("app.apk", "app (x).apk"));
        assertFalse(DownloadNamePolicy.matchesUniquifiedName("app.apk", "other (1).apk"));
        assertFalse(DownloadNamePolicy.matchesUniquifiedName("app.apk", "app (1).zip"));
    }
}
