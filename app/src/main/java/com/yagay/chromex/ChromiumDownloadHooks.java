package com.yagay.chromex;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * Shared download feature entrypoint. Stable cross-version services are installed outside this
 * class; only verified dialog/JNI presentation differences remain profile-specific here.
 */
final class ChromiumDownloadHooks {
    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    ChromiumDownloadHooks(ChromiumProfile profile, ChromeRuntime runtime, XposedModule module,
                          HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        if (profile.is152()) {
            new Chrome152DownloadHooks(runtime.classLoader, hooks, prefs).install();
            return;
        }

        // Chrome 145 keeps older R8/JNI bridge layouts. The feature entrypoint is shared, while
        // these small exact adapters remain isolated until their callbacks can be expressed through
        // stable Chromium interfaces.
        new DownloadDialogHooks(module, hooks, prefs, runtime.classLoader).install();
        new InstallerHooks(module, hooks, prefs, runtime.classLoader).install();
        new BannerHooks(module, hooks, prefs, runtime.classLoader).install();
    }
}
