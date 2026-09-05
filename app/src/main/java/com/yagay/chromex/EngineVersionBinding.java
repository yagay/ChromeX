package com.yagay.chromex;

/** Resolves the actual Chromium engine version before any feature capability probing. */
final class EngineVersionBinding {
    private static final String VERSION_INFO = "org.chromium.base.version_info.VersionInfo";

    private EngineVersionBinding() {}

    static String resolve(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return "unknown";

        String value = stableVersion(runtime.classLoader);
        if (ChromiumEngineVersionScanner.plausible(value)) {
            if (hooks != null) hooks.info("Chromium engine version bound from VersionInfo: " + value);
            return value;
        }

        value = hooks == null ? null : EngineVersionDexResolver.resolve(runtime, hooks);
        if (ChromiumEngineVersionScanner.plausible(value)) return value;

        if (hooks != null) {
            String scanned = ChromiumEngineVersionScanner.scan(runtime, hooks);
            if (ChromiumEngineVersionScanner.plausible(scanned)) return scanned;
        }

        return runtime.versionName == null || runtime.versionName.isBlank()
                ? "unknown" : runtime.versionName;
    }

    private static String stableVersion(ClassLoader loader) {
        try {
            Class<?> type = Reflect.cls(loader, VERSION_INFO);
            Object value = Reflect.callStatic(type, "getProductVersion");
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
