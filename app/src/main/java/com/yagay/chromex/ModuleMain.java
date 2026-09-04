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

        ClassLoader loader = param.getClassLoader();
        prefs = Config.fromModule(this);
        Diagnostics.beginSession(prefs, processName, getApiVersion(),
                getFrameworkName(), getFrameworkVersion());
        hooks = new HookSupport(this, prefs);

        new TabHooks(this, hooks, prefs, loader).install();
        new DownloadDialogHooks(this, hooks, prefs, loader).install();
        new InstallerHooks(this, hooks, prefs, loader).install();
        new BannerHooks(this, hooks, prefs, loader).install();

        Diagnostics.scheduleScan(prefs, loader);
        hooks.info("adaptive Chrome hooks installed; automatic locator scheduled");
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
