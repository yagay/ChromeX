package com.yagay.chromex;

import android.content.SharedPreferences;

/** Download feature composed entirely from universal semantic bindings. */
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
        new UniversalDownloadDialogs(profile, runtime, hooks, prefs).install();
        new UniversalDownloadHooks(profile, runtime, hooks, prefs).install();
    }
}
