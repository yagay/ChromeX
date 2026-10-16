package com.yagay.chromex;

/**
 * Isolates stock Chromium 158 from the previously installed ChromeX feature hooks,
 * so a crash cannot be incorrectly attributed to the read-only tablet symbol probe.
 * Existing ChromeX behavior for other browser packages and engine versions is unchanged.
 */
final class Chromium158IsolationPolicy {
    private Chromium158IsolationPolicy() {}

    static boolean shouldIsolate(String pkg, int major,
                                 boolean safeMode, boolean tabletScan) {
        return "org.chromium.chrome".equals(pkg)
                && major == 158
                && (safeMode || tabletScan);
    }
}
