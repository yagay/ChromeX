package com.yagay.chromex;

/** Backend descriptor for Chromium's official Desktop Android extension implementation. */
public final class GoogleDesktopExtensionBackend implements ExtensionBackend {
    private final ExtensionCapabilityReport report;
    private final GoogleDesktopExtensionBridgeResolver.Binding binding;

    public GoogleDesktopExtensionBackend(ExtensionCapabilityReport report, ClassLoader classLoader) {
        this.report = report;
        this.binding = GoogleDesktopExtensionBridgeResolver.resolve(classLoader);
    }

    @Override
    public ExtensionRuntimeMode mode() {
        return ExtensionRuntimeMode.GOOGLE_DESKTOP_FULL;
    }

    @Override
    public boolean isAvailable() {
        return report != null
                && report.mode == ExtensionRuntimeMode.GOOGLE_DESKTOP_FULL
                && binding != null
                && binding.actionsBridge != null;
    }

    boolean canUnlock() {
        return isAvailable() && binding.canUnlock();
    }

    GoogleDesktopExtensionBridgeResolver.Binding binding() {
        return binding;
    }

    @Override
    public String diagnostics() {
        return "GOOGLE_DESKTOP_FULL backend selected\navailable=" + isAvailable() + "\n"
                + (binding == null ? "bridge=null\n" : binding.diagnosticsText())
                + "directCrxInstall=false (use Chromium native Web Store/install pipeline)\n"
                + (report == null ? "no capability report" : report.toDiagnosticText());
    }
}
