package com.yagay.chromex;

/**
 * Chromium 158's tab strip requires tablet layout manager + native compositor + resource
 * dimensions. The old isolated DeviceFormFactor.isTablet() Hook crashed 158.0.8110.0.
 * This phase safely resolves obfuscated symbols before applying any layout changes.
 */
final class CromiteTabletUiHooks {
    private final ChromeRuntime runtime;
    private final HookSupport hooks;

    CromiteTabletUiHooks(ChromeRuntime runtime, HookSupport hooks) {
        this.runtime = runtime;
        this.hooks = hooks;
    }

    static boolean supportsSource158(String packageName, int versionMajor) {
        return "org.chromium.chrome".equals(packageName) && versionMajor == 158;
    }

    void install() {
        if (!supportsSource158(runtime.packageName, runtime.majorVersion)) {
            hooks.warn("tablet-ui: source matcher targets official Chromium 158 only; "
                    + "package=" + runtime.packageName + " version=" + runtime.versionName
                    + "; no Hook installed");
            return;
        }
        hooks.info("tablet-ui: Chromium 158 source analysis active; "
                + "crash-prone DeviceFormFactor and resources remain UNMODIFIED");
        new Chromium158TabletProbe(runtime, hooks).startAsync();
    }
}
