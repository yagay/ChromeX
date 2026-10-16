package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class Chromium158TabletModeTest {
    @Test
    public void sourceAnalysisIsScopedToOfficialChromium158() {
        assertTrue(CromiteTabletUiHooks.supportsSource158("org.chromium.chrome", 158));
        assertFalse(CromiteTabletUiHooks.supportsSource158("org.chromium.chrome", 157));
        assertFalse(CromiteTabletUiHooks.supportsSource158("org.cromite.cromite", 158));
        assertFalse(CromiteTabletUiHooks.supportsSource158("com.android.chrome", 158));
    }
}
