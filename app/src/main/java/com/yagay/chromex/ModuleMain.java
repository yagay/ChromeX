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

        installFeature("Chromium split bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
        RuntimeDiagnostics.flushPendingIfPossible();
        ChromiumProfile profile = ChromiumProfile.detect(runtime);

        installFeature("same-name download overwrite", () ->
                new SameNameOverwriteHooks(runtime, hooks, prefs).install());
        installFeature("download history rewrite", () ->
                new DownloadHistoryRewriteHooks(runtime, hooks, prefs).install());

        if (profile != null) {
            hooks.info("verified Chromium profile selected: " + profile.label()
                    + " " + runtime.versionName);
            installFeature("Chromium tabs/homepage", () ->
                    new ChromiumTabsHooks(profile, runtime.classLoader, hooks, prefs).install());
            installFeature("Chromium downloads", () ->
                    new ChromiumDownloadHooks(profile, runtime, hooks, prefs).install());
            hooks.info("shared Chromium feature profile active: " + profile.family);
        } else {
            hooks.info("no verified exact profile; enabling structural capability fallbacks");
            installFeature("adaptive Chromium hooks", () ->
                    new AdaptiveChromeHooks(this, hooks, prefs, runtime).install());
            installFeature("adaptive Chromium download dialogs", () ->
                    new AdaptiveDownloadDialogs(runtime, hooks, prefs).install());
        }

        try {
            Diagnostics.scheduleScan(prefs, runtime.classLoader);
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic locator scheduling failed", t);
        }
        hooks.info("ChromeX feature hooks installed after Chromium split ready");
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
