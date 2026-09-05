package com.yagay.chromex;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Completion/Toast/auto-open pipeline for unknown Chromium and vendor forks. */
final class AdaptiveDownloadCompletionHooks {
    private static final long COMPLETION_SETTLE_MS = 750L;
    private static final long COMPLETION_DEDUP_MS = 3000L;
    private static final long BANNER_WINDOW_MS = 3500L;
    private static final long TOAST_DEDUP_MS = 3000L;
    private static final long OPEN_DEDUP_MS = 3000L;
    private static final long OPEN_WAIT_MS = 20_000L;
    private static final long OPEN_POLL_MS = 500L;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> completions = new ConcurrentHashMap<>();
    private final AtomicLong suppressBannerUntil = new AtomicLong();
    private final AtomicInteger suppressBudget = new AtomicInteger();
    private final AtomicLong lastToastAt = new AtomicLong();
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final AtomicReference<OpenStamp> lastOpen = new AtomicReference<>();
    private final ExecutorService fileOpenWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ChromeX-adaptive-file-open");
        thread.setDaemon(true);
        return thread;
    });

    AdaptiveDownloadCompletionHooks(ChromiumProfile profile, ChromeRuntime runtime,
                                    HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        hookCompletionOwner(Chrome145.DOWNLOAD_CONTROLLER,
                "chromex:adaptive:download-controller-completed");
        hookCompletionOwner(Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "chromex:adaptive:download-manager-completed");
        installBannerSuppression();
        installTranslateSuppression();
    }

    private void hookCompletionOwner(String owner, String id) {
        hooks.all(runtime.classLoader, owner, "onDownloadCompleted", id, chain -> {
            Object info = findDownloadInfo(chain.getArgs().toArray());
            Object result = chain.proceed();
            if (info != null) {
                main.postDelayed(() -> handleCompletion(info, owner), COMPLETION_SETTLE_MS);
            }
            return result;
        });
    }

    private void handleCompletion(Object info, String source) {
        try {
            AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(info);
            if (!values.usable()) {
                hooks.warn("adaptive DownloadInfo unresolved from " + info.getClass().getName()
                        + " detail=" + values.detail);
                return;
            }

            String path = values.path;
            String name = values.name;
            String logical = DownloadNormalizationRegistry.logicalPath(path);
            if (logical != null) {
                path = logical;
                name = new File(logical).getName();
            }
            Artifact artifact = new Artifact(path, name, values.mime);
            if (!markCompletion(artifact.key())) return;

            String nameOrPath = artifact.name != null ? artifact.name : artifact.path;
            boolean apk = DownloadAutoOpenPolicy.isApk(artifact.mime, nameOrPath);
            boolean replaceBanner = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                    || (apk && Config.get(prefs, Config.APK_TOAST));
            if (replaceBanner) {
                suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
                suppressBudget.set(4);
                showToastOnce(artifact.displayName());
            }

            DownloadAutoOpenPolicy.Match match = DownloadAutoOpenPolicy.match(
                    prefs, artifact.mime, nameOrPath);
            if (match != null) enqueueOpen(artifact.path, artifact.name, match);

            hooks.info("adaptive download completion: " + artifact.displayName()
                    + " source=" + simpleName(source)
                    + " metadata=" + values.detail
                    + (match == null ? "" : " autoOpen=" + match.category));
        } catch (Throwable t) {
            hooks.error("adaptive download completion", t);
        }
    }

    private void installBannerSuppression() {
        hooks.all(runtime.classLoader,
                "org.chromium.chrome.browser.download.DownloadMessageUiControllerImpl",
                "onItemUpdated", "chromex:adaptive:banner-current", chain -> {
                    if (takeBannerSuppression(false)) return null;
                    return chain.proceed();
                });
    }

    private void installTranslateSuppression() {
        hooks.all(runtime.classLoader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex:adaptive:translate-create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
        hooks.all(runtime.classLoader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex:adaptive:translate-show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
    }

    private boolean takeBannerSuppression(boolean budgeted) {
        if (suppressBannerUntil.get() < System.currentTimeMillis()) return false;
        if (!budgeted) {
            suppressBannerUntil.set(0L);
            return true;
        }
        while (true) {
            int value = suppressBudget.get();
            if (value <= 0) return false;
            if (suppressBudget.compareAndSet(value, value - 1)) return true;
        }
    }

    private boolean markCompletion(String key) {
        long now = System.currentTimeMillis();
        Long old = completions.put(key, now);
        if (old != null && now - old < COMPLETION_DEDUP_MS) return false;
        if (completions.size() > 128) {
            completions.entrySet().removeIf(e -> now - e.getValue() > COMPLETION_DEDUP_MS * 4);
        }
        return true;
    }

    private void enqueueOpen(String path, String name, DownloadAutoOpenPolicy.Match match) {
        String fileName = normalizedName(name, path);
        if (fileName == null || match == null) return;
        String originalPath = path;
        fileOpenWorker.execute(() -> {
            long deadline = System.currentTimeMillis() + OPEN_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!Config.get(prefs, match.configKey)) return;
                String logical = DownloadNormalizationRegistry.logicalPath(originalPath);
                String candidatePath = logical == null ? originalPath : logical;
                String candidateName = logical == null ? fileName : new File(logical).getName();
                InstallerUriResolver.Result resolved = InstallerUriResolver.resolve(
                        runtime.application, runtime.classLoader, candidatePath, candidateName);
                if (resolved.uri != null) {
                    Uri uri = resolved.uri;
                    main.post(() -> launchFileNow(uri, match.mime, candidateName, match.category));
                    return;
                }
                if (resolved.terminal) {
                    hooks.warn(profile.label() + " adaptive auto-open stopped: " + candidateName
                            + " :: " + resolved.detail);
                    return;
                }
                try {
                    Thread.sleep(OPEN_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            hooks.warn(profile.label() + " adaptive file not resolvable after 20s: " + fileName);
        });
    }

    private boolean launchFileNow(Uri uri, String mime, String key, String category) {
        if (!InstallerUriResolver.isContent(uri)) return false;
        String safeMime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
        long now = System.currentTimeMillis();
        String safeKey = (key == null ? uri.toString() : key) + "|" + safeMime;
        OpenStamp previous = lastOpen.get();
        if (previous != null && previous.key.equals(safeKey)
                && now - previous.time < OPEN_DEDUP_MS) return true;
        OpenStamp next = new OpenStamp(safeKey, now);
        lastOpen.set(next);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, safeMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            runtime.application.startActivity(intent);
            hooks.info(profile.label() + " adaptive file opened: " + safeKey
                    + " category=" + category);
            return true;
        } catch (ActivityNotFoundException e) {
            lastOpen.compareAndSet(next, previous);
            showOpenFailure(key, "没有找到可打开此文件的应用");
            return false;
        } catch (Throwable t) {
            lastOpen.compareAndSet(next, previous);
            hooks.error("adaptive launch downloaded file", t);
            showOpenFailure(key, "自动打开失败");
            return false;
        }
    }

    private void showOpenFailure(String name, String message) {
        main.post(() -> {
            try {
                String safe = name == null || name.isBlank() ? "下载文件" : name;
                Toast.makeText(runtime.application, message + ": " + safe, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                hooks.error("show adaptive auto-open failure", t);
            }
        });
    }

    private void showToastOnce(String name) {
        long now = System.currentTimeMillis();
        String safe = name == null || name.isBlank() ? "下载文件" : name;
        if (safe.equals(lastToastName.get()) && now - lastToastAt.get() < TOAST_DEDUP_MS) return;
        lastToastName.set(safe);
        lastToastAt.set(now);
        main.post(() -> {
            try {
                Toast.makeText(runtime.application, "下载完成: " + safe, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                hooks.error("show adaptive download toast", t);
            }
        });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String normalizedName(String name, String path) {
        if (name != null && !name.isBlank()) return name;
        if (path == null || path.isBlank() || path.startsWith("content://")) return null;
        String clean = path.startsWith("file://") ? path.substring("file://".length()) : path;
        try {
            String value = new File(clean).getName();
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String simpleName(String value) {
        if (value == null) return "unknown";
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static final class Artifact {
        final String path;
        final String name;
        final String mime;

        Artifact(String path, String name, String mime) {
            this.path = path;
            this.name = name;
            this.mime = mime;
        }

        String displayName() {
            if (name != null && !name.isBlank()) return name;
            if (path != null && !path.isBlank()) return path;
            return "下载文件";
        }

        String key() {
            return String.valueOf(path) + "|" + String.valueOf(name) + "|" + String.valueOf(mime);
        }
    }

    private static final class OpenStamp {
        final String key;
        final long time;

        OpenStamp(String key, long time) {
            this.key = key;
            this.time = time;
        }
    }
}
