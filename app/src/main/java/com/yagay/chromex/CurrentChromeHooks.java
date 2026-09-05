package com.yagay.chromex;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * Small compatibility hooks discovered from runtime diagnostics on current Chrome builds.
 * Keep these narrow and evidence-based; broad R8 guesses belong in the locator, not here.
 */
final class CurrentChromeHooks {
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;

    CurrentChromeHooks(XposedModule module, HookSupport hooks,
                       SharedPreferences prefs, ClassLoader loader) {
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        installCommandLineNoRestore();
    }

    private void installCommandLineNoRestore() {
        // Chrome 152.0.7977.75 keeps org.chromium.base.CommandLine but R8 renames hasSwitch()
        // to c(String). Runtime diagnostics verified this exact signature.
        hooks.exact(loader, "org.chromium.base.CommandLine", "c",
                new Class<?>[]{String.class}, "chromex:tabs:no-restore:commandline-c", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
    }
}
