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

        // Chrome's browser Java code lives in an isolated feature split. PackageReadyParam only
        // describes the loader at that instant, so defer all browser hooks until the `chrome` split
        // is confirmed loadable.
        installFeature("Chrome split bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
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
            // Legacy short R8 names are strictly confined to the release they were verified on.
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
            // New/unknown Chrome builds never touch release-specific short names or numeric J.N
            // selectors. Stable boundaries + DexKit structural resolution are used instead.
            installFeature("adaptive Chrome hooks", () ->
                    new AdaptiveChromeHooks(this, hooks, prefs, runtime).install());
            installFeature("adaptive download dialogs", () ->
                    new AdaptiveDownloadDialogs(runtime, hooks, prefs).install());
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
