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
     * Standard Chrome downloads are native DownloadItems, so replacing only
     * DownloadManagerBridge.enqueueNewDownload does not affect the main download path.
     *
     * <p>ChromeX now hooks DownloadManagerService.onDownloadItemCreated(), cancels the just-created
     * native DownloadItem through Chrome's own cancelDownload JNI path, suppresses publishing that
     * cancelled item to the Java UI, and re-enqueues the GET request through Android
     * DownloadManager. The older DownloadManagerBridge hook remains installed only as a fallback for
     * the subset of downloads Chromium explicitly delegates to Android DownloadManager.</p>
     */
    private void installReplacementDownloader() {
        install("ChromeX native download takeover", () ->
                new ChromeXNativeDownloadTakeover(runtime, hooks, prefs).install());
        install("ChromeX Android download bridge fallback", () ->
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
