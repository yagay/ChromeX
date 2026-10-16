package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CromiteTabletUiSettingTest {
    @Test
    public void forceTabletUiMustBeExplicitlyEnabled() {
        assertFalse(Config.defaultValue(Config.FORCE_TABLET_UI));
        assertTrue(Config.defaultValue(Config.CLEAN_START));
    }
}
