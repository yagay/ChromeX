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
        } else {
            new AdaptiveDownloadDialogs(runtime, hooks, prefs).install();
        }
        new ChromiumDownloadCompletionHooks(profile, runtime, hooks, prefs).install();
    }
}
