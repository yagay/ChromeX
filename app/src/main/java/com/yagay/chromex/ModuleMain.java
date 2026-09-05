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

        installFeature("Chromium runtime bootstrap", () ->
                new ChromeBootstrap(this, hooks, prefs, targetPackage, param.getClassLoader(),
                        param.getApplicationInfo(), this::installForRuntime).install());
    }

    private void installForRuntime(ChromeRuntime runtime) {
        RuntimeDiagnostics.flushPendingIfPossible();
        ChromiumProfile profile = ChromiumProfile.resolve(runtime, hooks);
        if (profile == null) {
            hooks.warn("No Chromium engine profile for " + targetPackage
                    + " version=" + runtime.versionName);
            try { detach(); } catch (Throwable ignored) {}
            return;
        }

        hooks.info("Chromium engine fingerprint: profile=" + profile.label()
                + " package=" + targetPackage
                + " appVersion=" + runtime.versionName
                + " engine=" + profile.engineVersion);

        ResolvedBindings bindings =
                new ChromiumCapabilityResolver(profile, runtime, hooks).resolveBindings();
        BrowserCapabilities capabilities = bindings.capabilities;
        if (!capabilities.has(BrowserCapabilities.Key.CORE_RUNTIME, 60)) {
            hooks.warn("Target rejected after capability probe: not enough Chromium anchors :: "
                    + capabilities.get(BrowserCapabilities.Key.CORE_RUNTIME).detail);
            try { detach(); } catch (Throwable ignored) {}
            return;
        }

        new ChromiumFeatureOrchestrator(profile, bindings, runtime, hooks, prefs).install();

        try {
            Diagnostics.scheduleScan(prefs, runtime.classLoader);
        } catch (Throwable t) {
            log(Log.WARN, "ChromeX", "diagnostic locator scheduling failed", t);
        }

        scheduleExtensionCapabilityProbe(runtime.classLoader);
        hooks.info("Chromium capability runtime ready: package=" + targetPackage
                + " engine=" + profile.engineVersion);
    }

    private void scheduleExtensionCapabilityProbe(ClassLoader classLoader) {
        Thread worker = new Thread(() -> {
            try {
                ExtensionCapabilityReport report = ExtensionCapabilityDetector.detect(classLoader);
                RuntimeDiagnostics.event("INFO", "Extension capability probe\n"
                        + report.toDiagnosticText());
                hooks.info("Extension runtime mode=" + report.mode
                        + " java=" + report.javaHits.size()
                        + " native=" + report.nativeHits.size());
            } catch (Throwable t) {
                RuntimeDiagnostics.event("WARN", "Extension capability probe failed :: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                log(Log.WARN, "ChromeX", "extension capability probe failed", t);
            }
        }, "ChromeX-ExtensionProbe");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
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
    public boolean onHotReloading(HotReloadingParam param) { return true; }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (HookHandle handle : param.getOldHookHandles()) {
            try { handle.unhook(); } catch (Throwable ignored) {}
        }
        log(Log.INFO, "ChromeX", "old hooks removed after hot reload; restart target browser to reinstall");
    }
}
