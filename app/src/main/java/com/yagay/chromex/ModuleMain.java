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
        try {
            prefs = Config.fromModule(this); // read-only in the hooked Chrome process
        } catch (Throwable t) {
            prefs = null;
            log(Log.ERROR, "ChromeX", "remote preferences unavailable; using defaults", t);
        }
        hooks = new HookSupport(this, prefs);

        // Diagnostics must never be able to prevent the functional hooks from installing.
        try {
            Diagnostics.beginSession(prefs, processName, getApiVersion(),
                    getFrameworkName(), getFrameworkVersion());
        } catch (Throwable t) {
            log(Log.ERROR, "ChromeX", "diagnostic session initialization failed", t);
        }

        installFeature("tabs", () -> new TabHooks(this, hooks, prefs, loader).install());
        installFeature("download-dialogs",
                () -> new DownloadDialogHooks(this, hooks, prefs, loader).install());
        installFeature("installer", () -> new InstallerHooks(this, hooks, prefs, loader).install());
        installFeature("banners", () -> new BannerHooks(this, hooks, prefs, loader).install());

        try {
            Diagnostics.scheduleScan(prefs, loader);
        } catch (Throwable t) {
            log(Log.ERROR, "ChromeX", "automatic hook locator scheduling failed", t);
        }
        hooks.info("adaptive Chrome hooks installed; diagnostic IPC is fail-safe");
    }

    private void installFeature(String name, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            log(Log.ERROR, "ChromeX", "feature install failed: " + name, t);
            try {
                Diagnostics.event(prefs, "ERROR", "feature install failed: " + name + " :: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {}
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
