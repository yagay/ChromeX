package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class Chromium158IsolationPolicyTest {
    @Test
    public void chromium158DefaultsToSafeIsolation() {
        assertTrue(Config.defaultValue(Config.CHROMIUM158_SAFE_MODE));
        assertTrue(Chromium158IsolationPolicy.shouldIsolate(
                "org.chromium.chrome", 158, true, false));
    }

    @Test
    public void probeCannotEnableOldInterceptors() {
        assertTrue(Chromium158IsolationPolicy.shouldIsolate(
                "org.chromium.chrome", 158, false, true));
        assertFalse(Chromium158IsolationPolicy.shouldIsolate(
                "org.chromium.chrome", 158, false, false));
    }

    @Test
    public void otherBrowsersAndVersionsUnaffected() {
        assertFalse(Chromium158IsolationPolicy.shouldIsolate(
                "com.android.chrome", 158, true, true));
        assertFalse(Chromium158IsolationPolicy.shouldIsolate(
                "org.chromium.chrome", 157, true, true));
        assertFalse(Chromium158IsolationPolicy.shouldIsolate(
                "org.cromite.cromite", 158, true, true));
    }
}
