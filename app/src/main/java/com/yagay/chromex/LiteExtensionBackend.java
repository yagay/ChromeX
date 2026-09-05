package com.yagay.chromex;

/**
 * Compatibility backend for stock Chromium/Chrome builds without the native Extension Core.
 * Feature methods intentionally stay disabled until the corresponding content-script/storage/
 * runtime bridge is implemented.
 */
public final class LiteExtensionBackend implements ExtensionBackend {
    private final ExtensionCapabilityReport report;

    public LiteExtensionBackend(ExtensionCapabilityReport report) {
        this.report = report;
    }

    @Override
    public ExtensionRuntimeMode mode() {
        return ExtensionRuntimeMode.LITE;
    }

    @Override
    public boolean isAvailable() {
        return report != null && report.mode == ExtensionRuntimeMode.LITE;
    }

    @Override
    public String diagnostics() {
        return "LITE backend selected; compatibility services pending\n" +
                (report == null ? "no capability report" : report.toDiagnosticText());
    }
}
