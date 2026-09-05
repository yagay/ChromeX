package com.yagay.chromex;

/** Selects the safest usable backend based on the capability probe. */
public final class ExtensionBackendSelector {
    private ExtensionBackendSelector() {}

    public static ExtensionBackend select(ExtensionCapabilityReport report, ClassLoader classLoader) {
        if (report == null) return new UnavailableExtensionBackend();
        switch (report.mode) {
            case FULL: {
                NativeExtensionBackend nativeBackend = new NativeExtensionBackend(report, classLoader);
                if (nativeBackend.isAvailable()) return nativeBackend;
                // Native Extension Core may be present while a vendor exposes a different Java
                // bridge. Keep useful content-script support rather than failing the whole feature.
                return new LiteExtensionBackend(report);
            }
            case LITE:
                return new LiteExtensionBackend(report);
            case NONE:
            default:
                return new UnavailableExtensionBackend();
        }
    }

    private static final class UnavailableExtensionBackend implements ExtensionBackend {
        @Override
        public ExtensionRuntimeMode mode() {
            return ExtensionRuntimeMode.NONE;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String diagnostics() {
            return "NONE backend selected";
        }
    }
}
