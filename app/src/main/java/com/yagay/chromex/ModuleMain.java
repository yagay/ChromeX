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

        // These hooks target Chrome's browser/UI/download Java layer. Installing and scanning the
        // same targets in privileged/sandbox subprocesses produces false ClassNotFound reports and
        // lets a later subprocess overwrite the useful browser-process diagnostics.
        if (!Chrome145.PACKAGE.equals(processName)) {
            log(Log.INFO, "ChromeX", "skip secondary Chrome process: " + processName);
            return;
        }

        ClassLoader loader = param.getClassLoader();
        prefs = Config.fromModule(this);
        try {
            Diagnostics.beginSession(prefs, processName, getApiVersion(),
                    getFrameworkName(), getFrameworkVersion());
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic session init failed", t);
        }
        hooks = new HookSupport(this, prefs);

        installFeature("current compatibility", () ->
                new CurrentChromeHooks(this, hooks, prefs, loader).install());
        installFeature("tab hooks", () -> new TabHooks(this, hooks, prefs, loader).install());
        installFeature("download dialog hooks", () ->
                new DownloadDialogHooks(this, hooks, prefs, loader).install());
        installFeature("installer hooks", () ->
                new InstallerHooks(this, hooks, prefs, loader).install());
        installFeature("banner hooks", () ->
                new BannerHooks(this, hooks, prefs, loader).install());

        try {
            Diagnostics.scheduleScan(prefs, loader);
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic locator scheduling failed", t);
        }
        hooks.info("adaptive Chrome hooks installed in main process; diagnostic IPC is fail-safe");
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
