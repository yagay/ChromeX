package com.yagay.chromex;

import java.io.File;
import java.util.List;

/** FULL backend for Chromium forks that already ship a native extension runtime. */
public final class NativeExtensionBackend implements ExtensionBackend {
    private final ExtensionCapabilityReport report;
    private final NativeExtensionBridgeResolver.Binding binding;

    public NativeExtensionBackend(ExtensionCapabilityReport report, ClassLoader classLoader) {
        this.report = report;
        this.binding = NativeExtensionBridgeResolver.resolve(classLoader);
    }

    @Override
    public ExtensionRuntimeMode mode() {
        return ExtensionRuntimeMode.FULL;
    }

    @Override
    public boolean isAvailable() {
        return report != null && report.mode == ExtensionRuntimeMode.FULL
                && binding != null && binding.hasAnyCallableBridge();
    }

    @Override
    public List<String> getInstalledExtensionIds() {
        return NativeExtensionBridgeResolver.listExtensionIds(binding);
    }

    @Override
    public boolean installCrx(File crx) {
        return NativeExtensionBridgeResolver.installCrx(binding, crx);
    }

    @Override
    public boolean uninstall(String extensionId) {
        return NativeExtensionBridgeResolver.uninstall(binding, extensionId);
    }

    @Override
    public boolean executeAction(String extensionId) {
        return NativeExtensionBridgeResolver.executeAction(binding, extensionId);
    }

    @Override
    public String diagnostics() {
        return "FULL backend selected\navailable=" + isAvailable() + "\n"
                + (binding == null ? "bridge=null\n" : binding.diagnosticsText())
                + (report == null ? "no capability report" : report.toDiagnosticText());
    }
}
