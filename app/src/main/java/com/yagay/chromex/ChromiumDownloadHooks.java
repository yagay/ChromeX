package com.yagay.chromex;

import android.content.SharedPreferences;

/** Shared download feature composed from capability-level modules. */
final class ChromiumDownloadHooks {
    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    ChromiumDownloadHooks(ChromiumProfile profile, ChromeRuntime runtime,
                          HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        if (profile.isVerifiedExact()) {
            new ChromiumDownloadDialogs(profile, runtime.classLoader, hooks, prefs).install();
            new ChromiumDownloadCompletionHooks(profile, runtime, hooks, prefs).install();
        } else {
            // Vendor forks frequently keep semantic Chromium entry points but obfuscate the owner
            // classes/fields. Route them through the structural resolver instead of Chrome-specific
            // fallback selectors or DownloadInfo field names.
            new AdaptiveDownloadDialogsV2(runtime, hooks, prefs).install();
            new AdaptiveDownloadCompletionHooks(profile, runtime, hooks, prefs).install();
        }
    }
}
