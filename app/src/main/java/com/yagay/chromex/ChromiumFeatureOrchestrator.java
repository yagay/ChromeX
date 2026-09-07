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
        installReplacementDownloader();
        installTabs();
        installDownloads();
        hooks.info("capability-driven feature plan installed: package=" + runtime.packageName
                + " profile=" + profile.label());
    }

    /**
     * ChromeX now owns Chromium's Java -> Android DownloadManager enqueue boundary directly.
     *
     * <p>The former duplicate-dialog/completion-normalization/history-dedupe chain is intentionally
     * not installed here. The replacement bridge receives Chrome's final URL, cookie, referrer,
     * user-agent and filename request and performs the enqueue itself, making exact-name behavior
     * independent from DuplicateDownloadDialogBridge and post-completion filename repair.</p>
     */
    private void installReplacementDownloader() {
        install("ChromeX replacement downloader", () ->
                new ChromeXDownloadManagerBridge(runtime, hooks, prefs).install());
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
