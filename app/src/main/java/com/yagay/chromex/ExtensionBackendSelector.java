package com.yagay.chromex;

/** Selects the safest backend based on the capability probe. */
public final class ExtensionBackendSelector {
    private ExtensionBackendSelector() {}

    public static ExtensionBackend select(ExtensionCapabilityReport report) {
        if (report == null) return new UnavailableExtensionBackend();
        switch (report.mode) {
            case FULL:
                return new NativeExtensionBackend(report);
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
