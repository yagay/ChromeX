package com.yagay.chromex;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String VERIFIED_CHROME145 = "145.0.7632.218";

    private SharedPreferences prefs;
    private HookSupport hooks;
    private String processName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, "ChromeX", "module loaded in " + processName
                + ", build=" + BuildConfig.VERSION_NAME
                + " run=" + BuildConfig.BUILD_RUN
                + " sha=" + BuildConfig.BUILD_SHA
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Chrome145.PACKAGE.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        if (!Chrome145.PACKAGE.equals(processName)) {
            log(Log.INFO, "ChromeX", "skip secondary Chrome process: " + processName);
            try { detach(); } catch (Throwable ignored) {}
            return;
        }

        prefs = Config.fromModule(this);
        hooks = new HookSupport(this, prefs);
        RuntimeDiagnostics.beginSession(processName, getApiVersion(),
                getFrameworkName(), getFrameworkVersion());

        installFeature("Chrome split bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
        RuntimeDiagnostics.flushPendingIfPossible();
        ClassLoader loader = runtime.classLoader;

        installFeature("same-name download overwrite", () ->
                new SameNameOverwriteHooks(runtime, hooks, prefs).install());

        if (Chrome152.matches(runtime)) {
            hooks.info("verified exact-build profile selected: " + runtime.versionName);
            installFeature("Chrome 152 tabs/homepage", () ->
                    new Chrome152TabsHooks(loader, hooks, prefs).install());
            installFeature("Chrome 152 downloads", () ->
                    new Chrome152DownloadHooks(loader, hooks, prefs).install());
            hooks.info("Chrome 152 consolidated profile active: " + runtime.versionName);
        } else if (VERIFIED_CHROME145.equals(runtime.versionName)) {
            installFeature("Chrome 145 tabs/homepage", () ->
                    new Chrome145TabsHooks(hooks, prefs, loader).install());
            installFeature("Chrome 145 download dialog hooks", () ->
                    new DownloadDialogHooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 145 installer hooks", () ->
                    new InstallerHooks(this, hooks, prefs, loader).install());
            installFeature("Chrome 145 banner hooks", () ->
                    new BannerHooks(this, hooks, prefs, loader).install());
            hooks.info("verified Chrome 145 profile active: " + runtime.versionName);
        } else {
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
            RuntimeDiagnostics.event("ERROR", name + " failed during installation :: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (HookHandle handle : param.getOldHookHandles()) {
            try { handle.unhook(); } catch (Throwable ignored) {}
        }
        log(Log.INFO, "ChromeX", "old hooks removed after hot reload; restart Chrome to reinstall");
    }
}
