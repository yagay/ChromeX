package com.yagay.chromex;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private SharedPreferences prefs;
    private HookSupport hooks;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, "ChromeX", "module loaded in " + param.getProcessName()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Chrome145.PACKAGE.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        ClassLoader loader = param.getClassLoader();
        prefs = Config.fromModule(this);
        hooks = new HookSupport(this);

        new TabHooks(this, hooks, prefs, loader).install();
        new DownloadDialogHooks(this, hooks, prefs, loader).install();
        new InstallerHooks(this, hooks, prefs, loader).install();
        new BannerHooks(this, hooks, prefs, loader).install();

        log(Log.INFO, "ChromeX", "adaptive Chrome hooks installed; legacy Chrome 145 fallbacks enabled");
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
