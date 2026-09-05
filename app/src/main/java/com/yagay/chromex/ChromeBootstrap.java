package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Waits until the target Chromium browser's main split/classloader is actually ready. */
final class ChromeBootstrap {
    interface ReadyCallback {
        void onReady(ChromeRuntime runtime);
    }

    private static final long[] RETRIES_MS = {100L, 250L, 500L, 1000L, 2000L, 4000L, 7000L};

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final String targetPackage;
    private final ClassLoader initialLoader;
    private final ApplicationInfo applicationInfo;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private final ReadyCallback callback;

    ChromeBootstrap(XposedModule module, HookSupport hooks, SharedPreferences prefs,
                    String targetPackage, ClassLoader initialLoader, ApplicationInfo applicationInfo,
                    ReadyCallback callback) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.targetPackage = targetPackage;
        this.initialLoader = initialLoader;
        this.applicationInfo = applicationInfo;
        this.callback = callback;
    }

    void install() {
        hooks.info("bootstrap: waiting for Chromium target " + targetPackage);
        installApplicationCreateObserver();
        tryCurrentApplication("package-ready");
        for (long delay : RETRIES_MS) {
            main.postDelayed(() -> tryCurrentApplication("retry-" + delay), delay);
        }
    }

    private void installApplicationCreateObserver() {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation");
            Method method = instrumentation.getDeclaredMethod("callApplicationOnCreate", Application.class);
            method.setAccessible(true);
            hooks.method(method, "chromex:bootstrap:application-created", chain -> {
                Object result = chain.proceed();
                Object arg = chain.getArg(0);
                if (arg instanceof Application) {
                    Application app = (Application) arg;
                    if (targetPackage.equals(app.getPackageName())) {
                        tryReady(app, "Instrumentation.callApplicationOnCreate");
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            hooks.error("bootstrap Application observer", t);
        }
    }

    private void tryCurrentApplication(String reason) {
        if (delivered.get()) return;
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app instanceof Application
                    && targetPackage.equals(((Application) app).getPackageName())) {
                tryReady((Application) app, reason);
            }
        } catch (Throwable ignored) {}
    }

    private void tryReady(Application app, String reason) {
        if (delivered.get()) return;
        ClassLoader loader = resolveChromeLoader(app);
        if (loader == null || !hasBrowserAnchor(loader)) return;
        if (!delivered.compareAndSet(false, true)) return;

        String splitPath = findChromeSplitPath(applicationInfo);
        ChromeRuntime runtime = new ChromeRuntime(app, applicationInfo, loader, splitPath);
        hooks.info("bootstrap: Chromium split ready via " + reason
                + ", package=" + targetPackage
                + ", version=" + runtime.versionName
                + ", loader=" + loader.getClass().getName()
                + ", split=" + (splitPath == null ? "unknown" : splitPath));
        RuntimeDiagnostics.flushPendingIfPossible();
        try {
            callback.onReady(runtime);
        } catch (Throwable t) {
            hooks.error("bootstrap ready callback", t);
        }
    }

    private ClassLoader resolveChromeLoader(Application app) {
        String[] splitNames = applicationInfo == null ? null : applicationInfo.splitNames;
        if (splitNames != null) {
            for (String splitName : splitNames) {
                if (splitName == null) continue;
                String lower = splitName.toLowerCase();
                if (!lower.contains("chrome") && !lower.contains("browser")) continue;
                try {
                    Context split = app.createContextForSplit(splitName);
                    ClassLoader loader = split.getClassLoader();
                    if (loader != null && hasBrowserAnchor(loader)) return loader;
                } catch (Throwable ignored) {}
            }
        }

        for (String splitName : new String[]{"chrome", "browser"}) {
            try {
                Context split = app.createContextForSplit(splitName);
                ClassLoader loader = split.getClassLoader();
                if (loader != null && hasBrowserAnchor(loader)) return loader;
            } catch (Throwable ignored) {}
        }

        try {
            ClassLoader loader = app.getClassLoader();
            if (loader != null && hasBrowserAnchor(loader)) return loader;
        } catch (Throwable ignored) {}

        if (initialLoader != null && hasBrowserAnchor(initialLoader)) return initialLoader;
        return null;
    }

    /** Verify the stable Chromium ChromeTabbedActivity anchor before any feature Hook is installed. */
    private boolean hasBrowserAnchor(ClassLoader loader) {
        try {
            Class.forName(Chrome145.ACTIVITY, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String findChromeSplitPath(ApplicationInfo info) {
        if (info == null || info.splitSourceDirs == null) return null;
        String[] names = info.splitNames;
        if (names != null && names.length == info.splitSourceDirs.length) {
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                if (name != null) {
                    String lower = name.toLowerCase();
                    if (lower.contains("chrome") || lower.contains("browser")) {
                        return info.splitSourceDirs[i];
                    }
                }
            }
        }
        for (String path : info.splitSourceDirs) {
            if (path == null) continue;
            String lower = path.toLowerCase();
            if (lower.contains("chrome") || lower.contains("browser")) return path;
        }
        return null;
    }
}
