package com.yagay.chromex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import java.util.List;

public final class CromiteTabletUiSafetyTest {
    @Test
    public void realChromium158PatternMustFailClosed() {
        CromiteTabletUiSafety.Probe chrome158 = new CromiteTabletUiSafety.Probe() {
            public boolean hasClass(String name) {
                return CromiteTabletUiSafety.FORM.equals(name);
            }
            public boolean hasMethod(String owner, String name, Class<?> result, int count) {
                return owner.equals(CromiteTabletUiSafety.FORM) && name.equals("isTablet");
            }
            public int dimensionPx(String name) {
                return name.equals("control_container_height") ? 56 : 0;
            }
        };
        List<String> failures = CromiteTabletUiSafety.blockers(chrome158);
        assertFalse(failures.isEmpty());
        assertTrue(failures.contains("@dimen/tab_strip_height <= 0"));
        assertTrue(failures.contains("ToolbarLayout#getTabStripHeightFromResource"));
    }

    @Test
    public void gateOnlyAllowsCompleteClassesAndPackagedResources() {
        CromiteTabletUiSafety.Probe complete = new CromiteTabletUiSafety.Probe() {
            public boolean hasClass(String name) { return true; }
            public boolean hasMethod(String owner, String name, Class<?> result, int count) {
                return true;
            }
            public int dimensionPx(String name) { return 40; }
        };
        assertEquals(0, CromiteTabletUiSafety.blockers(complete).size());
    }
}
