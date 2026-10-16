package com.yagay.chromex;

import java.util.ArrayList;
import java.util.List;

/**
 * Mandatory all-or-nothing preflight before enabling Chromium's native tablet layout.
 *
 * Forcing DeviceFormFactor.isTablet() without native tab-strip classes and nonzero
 * phone-qualified resource dimensions can crash Chromium during its first Activity layout.
 * This gate intentionally prefers a disabled feature over a crashing browser.
 */
final class CromiteTabletUiSafety {
    static final String FORM = "org.chromium.ui.base.DeviceFormFactor";
    static final String THRESHOLD =
            "org.chromium.chrome.browser.toolbar.top.tab_strip.HeightTransitionHandler";
    static final String TOOLBAR =
            "org.chromium.chrome.browser.toolbar.top.ToolbarLayout";
    static final String STRIP =
            "org.chromium.chrome.browser.compositor.overlays.strip.StripLayoutHelperManager";
    static final String COORDINATOR =
            "org.chromium.chrome.browser.toolbar.top.tab_strip.TabStripTransitionCoordinator";
    static final String CONTROL =
            "org.chromium.chrome.browser.toolbar.top.ToolbarControlContainer";
    static final String ROOT =
            "org.chromium.chrome.browser.ui.RootUiCoordinator";

    interface Probe {
        boolean hasClass(String name);
        boolean hasMethod(String owner, String name, Class<?> returns, int parameters);
        int dimensionPx(String resourceName);
    }

    static List<String> blockers(Probe probe) {
        List<String> blocked = new ArrayList<>();
        requireMethod(probe, blocked, FORM, "isTablet", boolean.class, 0);
        requireMethod(probe, blocked, FORM,
                "isNonMultiDisplayContextOnTablet", boolean.class, 1);
        requireMethod(probe, blocked, FORM, "isWindowOnTablet", boolean.class, 1);
        requireMethod(probe, blocked, FORM, "detectScreenWidthBucket", int.class, 1);
        requireMethod(probe, blocked, FORM, "getNonMultiDisplayMinimumTabletWidthPx",
                int.class, 1);
        requireMethod(probe, blocked, FORM, "getMinimumTabletWidthPx", int.class, 1);
        requireMethod(probe, blocked, THRESHOLD, "getScreenWidthThresholdDp", int.class, 0);
        requireMethod(probe, blocked, TOOLBAR, "getTabStripHeightFromResource", int.class, 0);
        requireClass(probe, blocked, STRIP);
        requireClass(probe, blocked, COORDINATOR);
        requireMethod(probe, blocked, CONTROL, "initWithToolbar", void.class, 2);
        requireMethod(probe, blocked, ROOT, "getFindToolbarStub", int.class, 0);

        // Stock Chromium defines these as 0dp on phones. They are not changed by
        // LSPosed Java return-value hooks. Reject zero-height tablet composition.
        requireDimension(probe, blocked, "tab_strip_height");
        requireDimension(probe, blocked, "tab_strip_reserved_top_padding");
        requireDimension(probe, blocked, "control_container_height");
        return blocked;
    }

    private static void requireClass(Probe probe, List<String> failures, String name) {
        if (!probe.hasClass(name)) failures.add(name + " (class)");
    }

    private static void requireMethod(Probe probe, List<String> failures, String owner,
                                      String name, Class<?> returns, int parameters) {
        if (!probe.hasMethod(owner, name, returns, parameters)) {
            failures.add(owner.substring(owner.lastIndexOf('.') + 1) + "#" + name);
        }
    }

    private static void requireDimension(Probe probe, List<String> failures, String name) {
        if (probe.dimensionPx(name) <= 0) failures.add("@dimen/" + name + " <= 0");
    }

    private CromiteTabletUiSafety() {}
}
