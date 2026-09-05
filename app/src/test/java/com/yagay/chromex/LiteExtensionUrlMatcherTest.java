package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class LiteExtensionUrlMatcherTest {
    @Test
    public void chromeMatchPatterns() {
        assertTrue(LiteExtensionUrlMatcher.matches("https://example.com/a/b", "https://example.com/*"));
        assertTrue(LiteExtensionUrlMatcher.matches("https://sub.example.com/a", "*://*.example.com/*"));
        assertTrue(LiteExtensionUrlMatcher.matches("https://example.com/a", "*://*.example.com/*"));
        assertFalse(LiteExtensionUrlMatcher.matches("http://evil.example.net/a", "*://*.example.com/*"));
        assertFalse(LiteExtensionUrlMatcher.matches("ftp://example.com/a", "*://*.example.com/*"));
        assertTrue(LiteExtensionUrlMatcher.matches("file:///sdcard/test.html", "<all_urls>"));
    }

    @Test
    public void excludesOverrideIncludes() {
        assertFalse(LiteExtensionUrlMatcher.matchesAny(
                "https://example.com/private/a",
                Collections.singletonList("https://example.com/*"),
                Collections.singletonList("https://example.com/private/*")));
        assertTrue(LiteExtensionUrlMatcher.matchesAny(
                "https://example.com/public/a",
                Collections.singletonList("https://example.com/*"),
                Arrays.asList("https://example.com/private/*", "https://other.com/*")));
    }
}
