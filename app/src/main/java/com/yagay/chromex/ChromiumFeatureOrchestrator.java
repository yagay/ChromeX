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
        installSameNameOverwrite();
        installTabs();
        installDownloads();
        hooks.info("capability-driven feature plan installed: package=" + runtime.packageName
                + " profile=" + profile.label());
    }

    /**
     * Keep Chrome's native downloader and remove both duplicate-name barriers that can force a
     * generated "(1)" filename on Android:
     *
     * <ol>
     *   <li>DownloadCollectionBridge.fileNameExists(String) querying the Android download
     *       collection/MediaStore.</li>
     *   <li>Chromium native FilenameConflictAction at DownloadPathReservationTracker.</li>
     * </ol>
     *
     * <p>The experimental ChromeX re-download takeover is intentionally not installed while this
     * path is tested, so diagnostics cannot be confused by two competing download owners.</p>
     */
    private void installSameNameOverwrite() {
        if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) {
            skip("same-name overwrite", "disabled by user");
            return;
        }
        install("DownloadCollection duplicate bypass", () ->
                new DownloadCollectionConflictHooks(runtime, hooks, prefs).install());
        install("native conflict policy", () ->
                NativeDownloadConflictBridge.install(runtime, hooks));
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
