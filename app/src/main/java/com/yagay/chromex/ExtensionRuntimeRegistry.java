package com.yagay.chromex;

/** Process-local holder for the selected extension backend. */
final class ExtensionRuntimeRegistry {
    private static volatile ExtensionBackend backend;
    private static volatile ExtensionCapabilityReport report;

    private ExtensionRuntimeRegistry() {}

    static void set(ExtensionCapabilityReport capabilityReport, ExtensionBackend selectedBackend) {
        report = capabilityReport;
        backend = selectedBackend;
    }

    static ExtensionBackend backend() {
        return backend;
    }

    static ExtensionCapabilityReport report() {
        return report;
    }

    static void clear() {
        backend = null;
        report = null;
    }
}
