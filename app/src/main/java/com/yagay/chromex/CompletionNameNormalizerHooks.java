package com.yagay.chromex;

import android.content.SharedPreferences;
import android.media.MediaScannerConnection;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Completion-stage safety net for Chromium forks that bypass the duplicate dialog and directly
 * commit a uniquified "name (n).ext" file. When overwrite is enabled, replace the old logical
 * target with the newly completed file and publish the path normalization so history/UI bindings
 * can rewrite their metadata too.
 */
final class CompletionNameNormalizerHooks {
    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    CompletionNameNormalizerHooks(ChromiumProfile profile, ChromeRuntime runtime,
                                  HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installOwner(Chrome145.DOWNLOAD_CONTROLLER, "controller");
        installOwner(Chrome145.DOWNLOAD_MANAGER_SERVICE, "manager");
        hooks.info("completion numbered-name normalizer installed");
    }

    private void installOwner(String owner, String id) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, owner);
            if (Reflect.named(type, "onDownloadCompleted").isEmpty()) return;
        } catch (Throwable ignored) {
            return;
        }

        hooks.all(runtime.classLoader, owner, "onDownloadCompleted",
                "chromex:completion-name-normalizer:" + id, chain -> {
                    Object info = DownloadInfoAccessor.find(chain.getArgs().toArray(), runtime.classLoader);
                    Object result = chain.proceed();
                    if (Config.get(prefs, Config.OVERWRITE_DUPLICATE) && info != null) {
                        normalize(info);
                    }
                    return result;
                });
    }

    private void normalize(Object info) {
        try {
            DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
            File actual = asSafeFile(values.path);
            if (actual == null || !actual.isFile()) return;

            String originalName = DownloadNamePolicy.originalNameFromUniquified(actual.getName());
            if (originalName == null || originalName.equals(actual.getName())) return;

            File parent = actual.getParentFile();
            if (parent == null) return;
            File desired = new File(parent, originalName).getCanonicalFile();
            if (!isSafeSameDirectory(actual, desired)) return;

            replace(actual, desired);
            DownloadNormalizationRegistry.register(actual, desired);
            boolean metadataChanged = DownloadInfoAccessor.rewrite(info, profile, desired);
            refreshMedia(actual, desired);
            hooks.info("completion numbered name normalized: " + actual.getName()
                    + " -> " + desired.getName() + " metadataChanged=" + metadataChanged);
        } catch (Throwable t) {
            hooks.warn("completion numbered-name normalization failed: "
                    + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static File asSafeFile(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        try {
            File file = new File(path).getCanonicalFile();
            String p = file.getAbsolutePath();
            if (!p.startsWith("/storage/") && !p.startsWith("/sdcard/")) return null;
            return file;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSafeSameDirectory(File actual, File desired) {
        try {
            if (actual == null || desired == null || actual.equals(desired)) return false;
            File aParent = actual.getParentFile();
            File dParent = desired.getParentFile();
            return aParent != null && dParent != null
                    && aParent.getCanonicalFile().equals(dParent.getCanonicalFile());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void replace(File actual, File desired) throws Exception {
        try {
            Files.move(actual.toPath(), desired.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(actual.toPath(), desired.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!desired.isFile() || actual.exists()) {
            throw new IllegalStateException("post-move verification failed");
        }
    }

    private void refreshMedia(File oldFile, File newFile) {
        if (runtime.application == null) return;
        try {
            MediaScannerConnection.scanFile(runtime.application,
                    new String[]{oldFile.getAbsolutePath(), newFile.getAbsolutePath()},
                    null, null);
        } catch (Throwable ignored) {}
    }
}
