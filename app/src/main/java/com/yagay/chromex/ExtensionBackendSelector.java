package com.yagay.chromex;

/** Selects the safest usable backend based on the classified extension runtime family. */
public final class ExtensionBackendSelector {
    private ExtensionBackendSelector() {}

    public static ExtensionBackend select(ExtensionCapabilityReport report, ClassLoader classLoader) {
        if (report == null) return new UnavailableExtensionBackend();
        switch (report.mode) {
            case GOOGLE_DESKTOP_FULL: {
                GoogleDesktopExtensionBackend google =
                        new GoogleDesktopExtensionBackend(report, classLoader);
                if (google.isAvailable()) return google;
                return new LiteExtensionBackend(report);
            }
            case VENDOR_FULL: {
                NativeExtensionBackend vendor = new NativeExtensionBackend(report, classLoader);
                if (vendor.isAvailable()) return vendor;
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
