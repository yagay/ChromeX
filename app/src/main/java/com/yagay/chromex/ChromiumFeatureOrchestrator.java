package com.yagay.chromex;

import android.content.SharedPreferences;

/** Installs universal feature engines from one resolved semantic binding registry. */
final class ChromiumFeatureOrchestrator {
    private final ChromiumProfile profile;
    private final ResolvedBindings bindings;
    private final BrowserCapabilities capabilities;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private OfflineContentRenameBinding renameBinding;

    ChromiumFeatureOrchestrator(ChromiumProfile profile, ResolvedBindings bindings,
                                ChromeRuntime runtime, HookSupport hooks,
                                SharedPreferences prefs) {
        this.profile = profile;
        this.bindings = bindings;
        this.capabilities = bindings.capabilities;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installDownloadSourceBindings();
        installSameNameOverwrite();
        installDownloadHistory();
        installTabs();
        installDownloads();
        hooks.info("capability-driven feature plan installed: package=" + runtime.packageName
                + " profile=" + profile.label());
    }

    private void installDownloadSourceBindings() {
        if (!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 60)
                || !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_UI, 60)) return;
        install("OfflineContent source binding", () -> {
            renameBinding = new OfflineContentRenameBinding(profile, runtime, hooks);
            renameBinding.install();
        });
    }

    private void installSameNameOverwrite() {
        if (!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_DUPLICATE_CONFLICT, 70)
                || !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 70)
                || !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_COMPLETION, 70)) {
            skip("same-name overwrite", "duplicate/info/legacy-completion capability incomplete");
            return;
        }
        install("same-name overwrite", () ->
                new NativeFirstSameNameOverwriteHooks(
                        profile, runtime, hooks, prefs, renameBinding).install());
    }

    private void installDownloadHistory() {
        if (!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 70)) {
            skip("download history", "DownloadInfo unavailable");
            return;
        }
        install("download backend refresh", () ->
                new DownloadBackendRefreshBinding(runtime, hooks).install());
        install("download history", () ->
                new UniversalDownloadHistoryHooks(profile, runtime, hooks, prefs).install());
    }

    private void installTabs() {
        if (!capabilities.has(BrowserCapabilities.Key.TABBED_ACTIVITY, 60)
                || !capabilities.has(BrowserCapabilities.Key.TAB_MODEL, 60)) {
            skip("tabs/homepage", "tabbed activity or TabModel unavailable");
            return;
        }
        install("tabs/homepage", () ->
                new UniversalTabsHooks(profile, capabilities, runtime, hooks, prefs, bindings).install());
    }

    private void installDownloads() {
        if (!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 60)
                && !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_LIFECYCLE, 70)) {
            skip("downloads", "DownloadInfo and OfflineContent lifecycle unavailable");
            return;
        }
        install("downloads", () ->
                new ChromiumDownloadHooks(profile, runtime, hooks, prefs, bindings).install());
    }

    private void install(String name, Runnable installer) {
        try { installer.run(); }
        catch (Throwable t) { hooks.error("capability feature install failed: " + name, t); }
    }

    private void skip(String feature, String reason) {
        hooks.warn("capability feature skipped: " + feature + " :: " + reason);
    }
}
