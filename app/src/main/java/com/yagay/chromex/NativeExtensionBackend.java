package com.yagay.chromex;

/**
 * FULL backend placeholder. It becomes operational only after verified Java/JNI bridge
 * bindings are resolved for the target browser. Keeping this class inert avoids invoking
 * vendor-specific extension internals merely because native markers are present.
 */
public final class NativeExtensionBackend implements ExtensionBackend {
    private final ExtensionCapabilityReport report;

    public NativeExtensionBackend(ExtensionCapabilityReport report) {
        this.report = report;
    }

    @Override
    public ExtensionRuntimeMode mode() {
        return ExtensionRuntimeMode.FULL;
    }

    @Override
    public boolean isAvailable() {
        return report != null && report.mode == ExtensionRuntimeMode.FULL;
    }

    @Override
    public String diagnostics() {
        return "FULL backend selected; bridge binding pending\n" +
                (report == null ? "no capability report" : report.toDiagnosticText());
    }
}
