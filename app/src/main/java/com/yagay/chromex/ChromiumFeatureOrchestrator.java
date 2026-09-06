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
        installSameNameOverwriteFromEceff5b();
        installDownloadHistory();
        installTabs();
        installDownloads();
        hooks.info("capability-driven feature plan installed: package=" + runtime.packageName
                + " profile=" + profile.label());
    }

    /**
     * Same-name overwrite implementation restored from eceff5b20cb75749f8efdf8de9a327602dd263e3.
     * Adaptive Chromium installs its OfflineItem capture first, then consumes the duplicate dialog.
     * Verified Chrome builds use the original SameNameOverwriteHooks implementation from that commit.
     */
    private void installSameNameOverwriteFromEceff5b() {
        if (!capabilities.has(BrowserCapabilities.Key.DOWNLOAD_DUPLICATE_CONFLICT, 60)
                || !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_INFO, 60)
                || !capabilities.has(BrowserCapabilities.Key.DOWNLOAD_COMPLETION, 60)) {
            skip("same-name overwrite", "duplicate/info/completion capability incomplete");
            return;
        }
        install("same-name overwrite (eceff5b)", () -> {
            if (profile.isAdaptive()) {
                new AdaptiveOfflineItemDisplayHooks(runtime, hooks, prefs).install();
                new AdaptiveSameNameOverwriteHooks(runtime, hooks, prefs).install();
            } else {
                new SameNameOverwriteHooks(runtime, hooks, prefs).install();
            }
        });
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
