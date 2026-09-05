package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Waits until a Chromium-family target's real browser classloader is ready. */
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
            Method method = instrumentation.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
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
        ResolvedLoader resolved = resolveBrowserLoader(app);
        if (resolved == null || resolved.loader == null) return;
        if (!delivered.compareAndSet(false, true)) return;

        ChromeRuntime runtime = new ChromeRuntime(
                app, applicationInfo, resolved.loader, resolved.dexPath);
        hooks.info("bootstrap: Chromium runtime ready via " + reason
                + ", package=" + targetPackage
                + ", version=" + runtime.versionName
                + ", loader=" + resolved.loader.getClass().getName()
                + ", source=" + resolved.source
                + ", dex=" + (resolved.dexPath == null ? "unknown" : resolved.dexPath)
                + ", anchors=" + resolved.anchorScore);
        RuntimeDiagnostics.flushPendingIfPossible();
        try {
            callback.onReady(runtime);
        } catch (Throwable t) {
            hooks.error("bootstrap ready callback", t);
        }
    }

    /**
     * Do not assume that the Chromium code lives in a split named "chrome". Vendor browsers use
     * names such as browser, core, web, feature_* or arbitrary generated split names. Probe likely
     * splits first, then every split, and select by Chromium class anchors.
     */
    private ResolvedLoader resolveBrowserLoader(Application app) {
        String[] names = applicationInfo == null ? null : applicationInfo.splitNames;
        String[] paths = applicationInfo == null ? null : applicationInfo.splitSourceDirs;
        if (names != null && names.length > 0) {
            Set<Integer> order = new LinkedHashSet<>();
            for (int i = 0; i < names.length; i++) {
                String low = names[i] == null ? "" : names[i].toLowerCase();
                if (low.contains("chrome") || low.contains("browser") || low.contains("chromium")) {
                    order.add(i);
                }
            }
            for (int i = 0; i < names.length; i++) order.add(i);

            ResolvedLoader best = null;
            for (int index : order) {
                String split = names[index];
                if (split == null || split.isBlank()) continue;
                try {
                    Context splitContext = app.createContextForSplit(split);
                    ClassLoader loader = splitContext.getClassLoader();
                    int score = anchorScore(loader);
                    if (score < 4) continue;
                    String path = paths != null && index < paths.length ? paths[index] : null;
                    ResolvedLoader candidate = new ResolvedLoader(
                            loader, path, "split:" + split, score);
                    if (best == null || candidate.anchorScore > best.anchorScore) best = candidate;
                    if (score >= 8) return candidate;
                } catch (Throwable ignored) {}
            }
            if (best != null) return best;
        }

        try {
            ClassLoader loader = app.getClassLoader();
            int score = anchorScore(loader);
            if (score >= 4) {
                String source = applicationInfo == null ? null : applicationInfo.sourceDir;
                return new ResolvedLoader(loader, source, "base-apk", score);
            }
        } catch (Throwable ignored) {}

        if (initialLoader != null) {
            int score = anchorScore(initialLoader);
            if (score >= 4) {
                String source = applicationInfo == null ? null : applicationInfo.sourceDir;
                return new ResolvedLoader(initialLoader, source, "package-loader", score);
            }
        }
        return null;
    }

    private int anchorScore(ClassLoader loader) {
        if (loader == null) return 0;
        int score = 0;
        if (has(loader, Chrome145.ACTIVITY)) score += 5;
        if (has(loader, Chrome145.GURL)) score += 1;
        if (has(loader, Chrome145.DOWNLOAD_INFO)) score += 1;
        if (has(loader, Chrome145.TAB_MODEL_API)) score += 1;
        if (has(loader, Chrome145.WEB_CONTENTS)) score += 1;
        if (has(loader, Chrome145.PROFILE) || has(loader, Chrome145.PROFILE_MANAGER)) score += 1;
        return score;
    }

    private static boolean has(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Kept for callers/tests that only have ApplicationInfo; runtime selection uses anchor probing. */
    static String findChromeSplitPath(ApplicationInfo info) {
        if (info == null || info.splitSourceDirs == null) return null;
        String[] names = info.splitNames;
        if (names != null && names.length == info.splitSourceDirs.length) {
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                if (name == null) continue;
                String low = name.toLowerCase();
                if (low.contains("chrome") || low.contains("browser") || low.contains("chromium")) {
                    return info.splitSourceDirs[i];
                }
            }
        }
        return info.splitSourceDirs.length == 0 ? null : info.splitSourceDirs[0];
    }

    private static final class ResolvedLoader {
        final ClassLoader loader;
        final String dexPath;
        final String source;
        final int anchorScore;

        ResolvedLoader(ClassLoader loader, String dexPath, String source, int anchorScore) {
            this.loader = loader;
            this.dexPath = dexPath;
            this.source = source;
            this.anchorScore = anchorScore;
        }
    }
}
