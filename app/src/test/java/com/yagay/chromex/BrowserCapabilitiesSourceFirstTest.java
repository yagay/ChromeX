package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowserCapabilitiesSourceFirstTest {
    @Test
    public void newSourceCapabilitiesDefaultToUnavailable() {
        BrowserCapabilities capabilities = BrowserCapabilities.builder().build();
        assertTrue(!capabilities.has(BrowserCapabilities.Key.NEW_TAB_SOURCE));
        assertTrue(!capabilities.has(BrowserCapabilities.Key.TAB_STATE_READY));
        assertTrue(!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_LIFECYCLE));
        assertTrue(!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_LOCATION_POLICY));
        assertEquals(BrowserCapabilities.Source.UNAVAILABLE,
                capabilities.get(BrowserCapabilities.Key.RECENTLY_CLOSED).source);
    }
}
