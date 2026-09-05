package com.yagay.chromex;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private SharedPreferences prefs;
    private HookSupport hooks;
    private String processName = "unknown";
    private String targetPackage = "unknown";

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
        prefs = Config.fromModule(this);
        String packageName = param.getPackageName();
        if (!ChromiumTargets.isAllowedTarget(packageName, prefs)) return;
        if (!param.isFirstPackage()) return;

        targetPackage = packageName;
        if (!packageName.equals(processName)) {
            log(Log.INFO, "ChromeX", "skip secondary Chromium process: package="
                    + packageName + " process=" + processName);
            try { detach(); } catch (Throwable ignored) {}
            return;
        }

        hooks = new HookSupport(this, prefs);
        RuntimeDiagnostics.beginSession(processName, getApiVersion(),
                getFrameworkName(), getFrameworkVersion());
        hooks.info("Chromium target accepted: " + targetPackage);

        installFeature("Chromium split bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, targetPackage, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
        RuntimeDiagnostics.flushPendingIfPossible();
        ChromiumProfile profile = ChromiumProfile.resolve(runtime, hooks);
        if (profile == null) {
            hooks.warn("No compatible Chromium profile for " + targetPackage
                    + " version=" + runtime.versionName);
            try { detach(); } catch (Throwable ignored) {}
            return;
        }

        hooks.info("Chromium capability profile selected: " + profile.label()
                + " package=" + targetPackage
                + " appVersion=" + runtime.versionName
                + " engine=" + profile.engineVersion);

        installFeature("same-name download overwrite", () -> {
            if (profile.isAdaptive()) {
                new AdaptiveSameNameOverwriteHooks(runtime, hooks, prefs).install();
            } else {
                new SameNameOverwriteHooks(runtime, hooks, prefs).install();
            }
        });
        installFeature("download history rewrite", () -> {
            new DownloadHistoryRewriteHooks(runtime, hooks, prefs).install();
            if (profile.isAdaptive()) {
                new AdaptiveDownloadHistoryCompat(runtime, hooks, prefs).install();
            }
        });
        installFeature("Chromium tabs/homepage", () -> {
            if (profile.isAdaptive()) {
                new AdaptiveHomepageValueHooks(runtime, hooks, prefs).install();
                new AdaptiveChromiumTabsHooks(profile, runtime, hooks, prefs).install();
                new AdaptiveForkTabCompat(profile, runtime, hooks, prefs).install();
            } else {
                new ChromiumTabsHooks(profile, runtime, hooks, prefs).install();
            }
        });
        installFeature("Chromium downloads", () ->
                new ChromiumDownloadHooks(profile, runtime, hooks, prefs).install());

        try {
            Diagnostics.scheduleScan(prefs, runtime.classLoader);
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic locator scheduling failed", t);
        }
        hooks.info("shared Chromium feature layers installed: " + profile.family
                + " package=" + targetPackage + " engine=" + profile.engineVersion);
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
        log(Log.INFO, "ChromeX", "old hooks removed after hot reload; restart target browser to reinstall");
    }
}
