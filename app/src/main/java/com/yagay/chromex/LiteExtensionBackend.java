package com.yagay.chromex;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Compatibility backend for stock Chromium/Chrome builds without native Extension Core. */
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
        return report != null && report.mode == ExtensionRuntimeMode.LITE && context() != null;
    }

    @Override
    public List<String> getInstalledExtensionIds() {
        Context context = context();
        return context == null ? Collections.emptyList() : LiteExtensionStore.listIds(context);
    }

    @Override
    public boolean installCrx(File crx) {
        Context context = context();
        return context != null && LiteExtensionStore.install(context, crx);
    }

    @Override
    public boolean uninstall(String extensionId) {
        Context context = context();
        if (context == null) return false;
        boolean removed = LiteExtensionStore.uninstall(context, extensionId);
        if (removed) LiteExtensionState.remove(context, extensionId);
        return removed;
    }

    @Override
    public boolean setEnabled(String extensionId, boolean enabled) {
        Context context = context();
        return context != null && getInstalledExtensionIds().contains(extensionId)
                && LiteExtensionState.setEnabled(context, extensionId, enabled);
    }

    @Override
    public String diagnostics() {
        Context context = context();
        int enabled = 0;
        if (context != null) {
            for (String id : getInstalledExtensionIds()) {
                if (LiteExtensionState.isEnabled(context, id)) enabled++;
            }
        }
        return "LITE backend selected\navailable=" + isAvailable()
                + "\ninstalled=" + getInstalledExtensionIds().size()
                + "\nenabled=" + enabled + "\n"
                + "supported=content_scripts(js/css),matches,exclude_matches,"
                + "chrome.runtime.id,chrome.storage.local(polyfill),enable/disable\n"
                + "limitations=main-frame/page-world/document-end compatibility;"
                + " no Extension Core/service-worker/webRequest\n"
                + (report == null ? "no capability report" : report.toDiagnosticText());
    }

    private static Context context() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
