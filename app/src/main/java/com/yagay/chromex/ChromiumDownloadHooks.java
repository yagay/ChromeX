package com.yagay.chromex;

import android.content.SharedPreferences;

/** Download feature composed from source-first semantic bindings plus safe fallbacks. */
final class ChromiumDownloadHooks {
    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ResolvedBindings bindings;

    ChromiumDownloadHooks(ChromiumProfile profile, ChromeRuntime runtime,
                          HookSupport hooks, SharedPreferences prefs,
                          ResolvedBindings bindings) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
        this.bindings = bindings;
    }

    void install() {
        new DownloadLocationPolicyBinding(
                runtime, hooks, prefs, bindings.downloadPromptGetter).install();
        new UniversalDownloadDialogs(profile, runtime, hooks, prefs).install();
        new UniversalDownloadHooks(profile, runtime, hooks, prefs, bindings).install();
    }
}
