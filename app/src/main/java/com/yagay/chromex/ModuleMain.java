package com.yagay.chromex;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private SharedPreferences prefs;
    private HookSupport hooks;
    private String processName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, "ChromeX", "module loaded in " + processName
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Chrome145.PACKAGE.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        // Browser/UI/download hooks belong only in Chrome's main browser process.
        if (!Chrome145.PACKAGE.equals(processName)) {
            log(Log.INFO, "ChromeX", "skip secondary Chrome process: " + processName);
            try {
                detach();
            } catch (Throwable ignored) {}
            return;
        }

        prefs = Config.fromModule(this);
        hooks = new HookSupport(this, prefs);
        try {
            Diagnostics.beginSession(prefs, processName, getApiVersion(),
                    getFrameworkName(), getFrameworkVersion());
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic session init failed", t);
        }

        // Do NOT install browser hooks using PackageReadyParam#getClassLoader directly. Chrome uses
        // isolated feature splits and the `chrome` split becomes available later during Application
        // startup. Bootstrap waits for that real split ClassLoader and only then installs features.
        installFeature("Chrome split bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
        // Re-emit a session after Application/context and split loader are ready. This guarantees
        // the exported report records the real Chrome version and the loader used for hooks.
        try {
            Diagnostics.beginSession(prefs, processName, getApiVersion(),
                    getFrameworkName(), getFrameworkVersion());
        } catch (Throwable ignored) {}

        ClassLoader loader = runtime.classLoader;
        if (runtime.is152()) {
            installFeature("Chrome 152 verified profile", () ->
                    new Chrome152Hooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 152 runtime corrections", () ->
                    new Chrome152Corrections(this, hooks, prefs, loader).install());
        } else if (runtime.is145()) {
            // Only Chrome 145 may use the old short R8 symbols in these compatibility classes.
            installFeature("Chrome 145 tab hooks", () ->
                    new TabHooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 145 download dialog hooks", () ->
                    new DownloadDialogHooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 145 installer hooks", () ->
                    new InstallerHooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 145 banner hooks", () ->
                    new BannerHooks(this, hooks, prefs, loader).install());
            hooks.info("Chrome 145 compatibility profile active: " + runtime.versionName);
        } else {
            // Unknown/new Chrome builds never touch release-specific short R8 names. Stable Java
            // boundaries and DexKit structural lookups are used instead and unsupported features
            // fail independently rather than risking hooks on unrelated classes.
            installFeature("adaptive Chrome hooks", () ->
                    new AdaptiveChromeHooks(this, hooks, prefs, runtime).install());
        }

        try {
            Diagnostics.scheduleScan(prefs, loader);
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic locator scheduling failed", t);
        }
        hooks.info("ChromeX feature hooks installed after chrome split ready");
    }

    private void installFeature(String name, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            log(Log.ERROR, "ChromeX", name + " failed during installation", t);
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {}
        }
        log(Log.INFO, "ChromeX", "old hooks removed after hot reload; restart Chrome to reinstall");
    }
}
