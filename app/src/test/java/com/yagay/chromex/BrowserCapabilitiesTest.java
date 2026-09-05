package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowserCapabilitiesTest {
    @Test
    public void capabilityMapUsesConfidenceAndDefaultsMissingEntries() {
        BrowserCapabilities capabilities = BrowserCapabilities.builder()
                .available(BrowserCapabilities.Key.DOWNLOAD_INFO,
                        BrowserCapabilities.Source.STRUCTURAL, 92, "shape")
                .build();

        assertTrue(capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO));
        assertTrue(capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 90));
        assertFalse(capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 95));
        assertFalse(capabilities.has(BrowserCapabilities.Key.TAB_CREATOR));
        assertEquals(BrowserCapabilities.Source.UNAVAILABLE,
                capabilities.get(BrowserCapabilities.Key.TAB_CREATOR).source);
    }

    @Test
    public void conflictPolicyIsDerivedFromUniversalDownloadAnchors() {
        BrowserCapabilities capabilities = BrowserCapabilities.builder()
                .available(BrowserCapabilities.Key.DOWNLOAD_INFO,
                        BrowserCapabilities.Source.STRUCTURAL, 94, "DownloadInfo")
                .available(BrowserCapabilities.Key.DOWNLOAD_COMPLETION,
                        BrowserCapabilities.Source.STABLE_API, 90, "completion")
                .available(BrowserCapabilities.Key.DOWNLOAD_DUPLICATE_CONFLICT,
                        BrowserCapabilities.Source.STABLE_API, 90, "duplicate")
                .build();

        assertTrue(capabilities.has(BrowserCapabilities.Key.DOWNLOAD_CONFLICT_POLICY, 90));
        assertEquals(BrowserCapabilities.Source.STRUCTURAL,
                capabilities.get(BrowserCapabilities.Key.DOWNLOAD_CONFLICT_POLICY).source);
    }
}
