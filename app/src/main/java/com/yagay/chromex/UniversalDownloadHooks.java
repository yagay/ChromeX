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

/** Source-first download completion/action engine with legacy completion fallbacks. */
final class UniversalDownloadHooks {
    private static final long COMPLETION_SETTLE_MS = 750L;
    private static final long COMPLETION_DEDUP_MS = 3000L;
    private static final long BANNER_WINDOW_MS = 3500L;
    private static final long TOAST_DEDUP_MS = 3000L;
    private static final long OPEN_DEDUP_MS = 15_000L;
    private static final long OPEN_WAIT_MS = 20_000L;
    private static final long OPEN_POLL_MS = 500L;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ResolvedBindings bindings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> completions = new ConcurrentHashMap<>();
    private final AtomicLong suppressBannerUntil = new AtomicLong();
    private final AtomicInteger suppressBudget = new AtomicInteger();
    private final AtomicLong lastToastAt = new AtomicLong();
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final AtomicReference<OpenStamp> lastOpen = new AtomicReference<>();
    private final ExecutorService fileOpenWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ChromeX-download-open");
        thread.setDaemon(true);
        return thread;
    });
    private OfflineContentLifecycleBinding lifecycleBinding;

    UniversalDownloadHooks(ChromiumProfile profile, ChromeRuntime runtime,
                           HookSupport hooks, SharedPreferences prefs,
                           ResolvedBindings bindings) {
        this.profile = profile;
        this.runtime = runtime;
        this.loader = runtime.classLoader;
        this.hooks = hooks;
        this.prefs = prefs;
        this.bindings = bindings;
    }

    void install() {
        installOfflineLifecycle();
        hookCompletionOwner(Chrome145.DOWNLOAD_CONTROLLER,
                "chromex:universal:download-controller-completed");
        hookCompletionOwner(Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "chromex:universal:download-manager-completed");
        installOpenDownload();
        installBannerSuppression();
        installTranslateSuppression();
        if (profile.is145()) install145Fallbacks();
    }

    private void installOfflineLifecycle() {
        if (bindings == null || bindings.offlineItemUpdated == null) return;
        lifecycleBinding = new OfflineContentLifecycleBinding(
                runtime, hooks, bindings, this::handleOfflineCompletion);
        if (!lifecycleBinding.install()) lifecycleBinding = null;
    }

    private void hookCompletionOwner(String owner, String id) {
        if (!hasMethod(owner, "onDownloadCompleted")) return;
        hooks.all(loader, owner, "onDownloadCompleted", id, chain -> {
            Object info = DownloadInfoAccessor.find(chain.getArgs().toArray(), loader);
            Object result = chain.proceed();
            if (info != null) main.postDelayed(() -> handleCompletion(info, owner), COMPLETION_SETTLE_MS);
            return result;
        });
    }

    private void handleOfflineCompletion(Object item, OfflineContentLifecycleBinding source) {
        try {
            OfflineItemAccessor.Values values = OfflineItemAccessor.read(item);
            if (!values.usable()) return;
            String path = values.path;
            String name = values.name;
            String logical = DownloadNormalizationRegistry.logicalPath(path);
            if (logical != null) {
                path = logical;
                name = new File(logical).getName();
            }
            processArtifact(new Artifact(path, name, values.mime, values.contentKey),
                    "OfflineContentAggregator", values.detail, source, item);
        } catch (Throwable t) {
            hooks.error("OfflineContent download completion", t);
        }
    }

    private void handleCompletion(Object info, String source) {
        try {
            DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
            if (!values.usable()) {
                hooks.warn("universal DownloadInfo unresolved: type=" + info.getClass().getName()
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
            processArtifact(new Artifact(path, name, values.mime, null),
                    simpleName(source), values.detail, null, null);
        } catch (Throwable t) {
            hooks.error("universal download completion", t);
        }
    }

    private void processArtifact(Artifact artifact, String source, String metadata,
                                 OfflineContentLifecycleBinding lifecycle, Object offlineItem) {
        if (artifact == null || !markCompletion(artifact.key())) return;
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
        boolean sourceOpened = false;
        if (match != null && lifecycle != null && offlineItem != null && !apk) {
            sourceOpened = lifecycle.open(offlineItem);
        }
        if (match != null && !sourceOpened) {
            enqueueOpen(artifact.path, artifact.name, match);
        }

        hooks.info("download completion through universal pipeline: "
                + artifact.displayName() + " source=" + source
                + " metadata=" + metadata
                + (match == null ? "" : " autoOpen=" + match.category
                        + (sourceOpened ? ":offline-source" : ":uri-fallback")));
    }

    /** Keep manual open aligned with the same selected auto-open policy on every fork exposing it. */
    private void installOpenDownload() {
        if (!hasMethod(Chrome145.DOWNLOAD_UTILS, "openDownload")) return;
        hooks.all(loader, Chrome145.DOWNLOAD_UTILS, "openDownload",
                "chromex:universal:download-open", chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length < 1) return chain.proceed();
                    String path = firstPath(args);
                    String mime = firstMime(args);
                    String name = lastFileName(args);
                    String logical = DownloadNormalizationRegistry.logicalPath(path);
                    if (logical != null) {
                        path = logical;
                        name = new File(logical).getName();
                    }
                    DownloadAutoOpenPolicy.Match match = DownloadAutoOpenPolicy.match(
                            prefs, mime, name != null ? name : path);
                    if (match == null) return chain.proceed();
                    String fileName = normalizedName(name, path);
                    InstallerUriResolver.Result resolved = InstallerUriResolver.resolve(
                            runtime.application, loader, path, fileName);
                    if (resolved.uri != null
                            && launchFileNow(resolved.uri, match.mime, fileName, match.category)) {
                        return null;
                    }
                    return chain.proceed();
                });
    }

    private void installBannerSuppression() {
        String current = "org.chromium.chrome.browser.download.DownloadMessageUiControllerImpl";
        if (hasMethod(current, "onItemUpdated")) {
            hooks.all(loader, current, "onItemUpdated", "chromex:universal:banner-current", chain -> {
                if (takeBannerSuppression(false)) return null;
                return chain.proceed();
            });
        }
        if (profile.is152()) {
            for (String method : new String[]{"a", "d"}) {
                if (!hasMethod(Chrome152.DOWNLOAD_MESSAGE, method)) continue;
                hooks.all(loader, Chrome152.DOWNLOAD_MESSAGE, method,
                        "chromex:universal:banner:152:" + method, chain -> {
                            if (takeBannerSuppression(true)) return null;
                            return chain.proceed();
                        });
            }
        }
    }

    private void installTranslateSuppression() {
        if (hasMethod(Chrome145.TRANSLATE_MESSAGE, "create")) {
            hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                    "chromex:universal:translate-create", chain -> {
                        if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                        return chain.proceed();
                    });
        }
        if (hasMethod(Chrome145.TRANSLATE_MESSAGE, "showMessage")) {
            hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                    "chromex:universal:translate-show", chain -> {
                        if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                        return chain.proceed();
                    });
        }
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

    /** Exact Chrome 145 completion/banner fallbacks remain last-resort signals. */
    private void install145Fallbacks() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            Class<?> visuals = Reflect.cls(loader, Chrome145.OFFLINE_VISUALS);
            hooks.exact(loader, Chrome145.OFFLINE_COMPLETE, "f",
                    new Class<?>[]{item, visuals}, "chromex:universal:download:offline145", chain -> {
                        Object result = chain.proceed();
                        Object value = chain.getArg(0);
                        if (value != null) {
                            try {
                                int state = Reflect.getInt(value, "m0");
                                if (state == 1 || state == 2) {
                                    handleLegacyCompletion(stringField(value, "f0"),
                                            stringField(value, "P"), stringField(value, "e0"));
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
        } catch (Throwable ignored) {}

        try {
            hooks.exact(loader, Chrome145.DOWNLOAD_EVENT_RUNNABLE, "run", new Class<?>[0],
                    "chromex:universal:download:event145", chain -> {
                        Object result = chain.proceed();
                        try {
                            Object event = Reflect.get(chain.getThisObject(), "P");
                            if (event != null && Reflect.getInt(event, "a") == 1) {
                                Object info = Reflect.get(event, "b");
                                if (info != null) main.postDelayed(
                                        () -> handleCompletion(info, Chrome145.DOWNLOAD_EVENT_RUNNABLE),
                                        COMPLETION_SETTLE_MS);
                            }
                        } catch (Throwable ignored) {}
                        return result;
                    });
        } catch (Throwable ignored) {}

        try {
            Class<?> model = Reflect.cls(loader, Chrome145.PROPERTY_MODEL);
            hooks.exact(loader, Chrome145.MESSAGE_DISPATCHER, "c",
                    new Class<?>[]{model, boolean.class},
                    "chromex:universal:banner:dispatch145", chain -> {
                        if (takeBannerSuppression(false)) return null;
                        return chain.proceed();
                    });
        } catch (Throwable ignored) {}
    }

    private void handleLegacyCompletion(String mime, String path, String name) {
        String logical = DownloadNormalizationRegistry.logicalPath(path);
        if (logical != null) {
            path = logical;
            name = new File(logical).getName();
        }
        processArtifact(new Artifact(path, name, mime, null),
                "Chrome145-fallback", "exact", null, null);
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
                        runtime.application, loader, candidatePath, candidateName);
                if (resolved.uri != null) {
                    Uri uri = resolved.uri;
                    main.post(() -> launchFileNow(uri, match.mime, candidateName, match.category));
                    return;
                }
                if (resolved.terminal) {
                    hooks.warn(profile.label() + " auto-open stopped: " + candidateName
                            + " category=" + match.category + " :: " + resolved.detail);
                    return;
                }
                try { Thread.sleep(OPEN_POLL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            hooks.warn(profile.label() + " file not resolvable after 20s: " + fileName);
        });
    }

    private boolean launchFileNow(Uri uri, String mime, String key, String category) {
        if (!InstallerUriResolver.isContent(uri)) return false;
        String safeMime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
        long now = System.currentTimeMillis();
        String safeKey = (key == null ? uri.toString() : key) + '|' + safeMime;
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
            hooks.info(profile.label() + " file opened: " + safeKey + " category=" + category);
            return true;
        } catch (ActivityNotFoundException e) {
            lastOpen.compareAndSet(next, previous);
            showOpenFailure(key, "没有找到可打开此文件的应用");
            return false;
        } catch (Throwable t) {
            lastOpen.compareAndSet(next, previous);
            hooks.error("launch downloaded file", t);
            showOpenFailure(key, "自动打开失败");
            return false;
        }
    }

    private void showOpenFailure(String name, String message) {
        main.post(() -> {
            try {
                String safe = name == null || name.isBlank() ? "下载文件" : name;
                Toast.makeText(runtime.application, message + ": " + safe, Toast.LENGTH_LONG).show();
            } catch (Throwable t) { hooks.error("show auto-open failure", t); }
        });
    }

    private void showToastOnce(String name) {
        long now = System.currentTimeMillis();
        String safe = name == null || name.isBlank() ? "下载文件" : name;
        if (safe.equals(lastToastName.get()) && now - lastToastAt.get() < TOAST_DEDUP_MS) return;
        lastToastName.set(safe);
        lastToastAt.set(now);
        main.post(() -> {
            try { Toast.makeText(runtime.application, "下载完成: " + safe, Toast.LENGTH_SHORT).show(); }
            catch (Throwable t) { hooks.error("show download toast", t); }
        });
    }

    private boolean hasMethod(String className, String methodName) {
        try { return !Reflect.named(Reflect.cls(loader, className), methodName).isEmpty(); }
        catch (Throwable ignored) { return false; }
    }

    private static String firstPath(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && AdaptiveDownloadInfo.looksLikePath((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private static String firstMime(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && AdaptiveDownloadInfo.looksLikeMime((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private static String lastFileName(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String
                    && AdaptiveDownloadInfo.looksLikeFileName((String) args[i])) {
                return (String) args[i];
            }
        }
        return null;
    }

    private static String normalizedName(String name, String path) {
        if (name != null && !name.isBlank()) return name;
        if (path == null || path.isBlank() || path.startsWith("content://")) return null;
        String clean = path.startsWith("file://") ? path.substring("file://".length()) : path;
        try {
            String value = new File(clean).getName();
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable ignored) { return null; }
    }

    private static String stringField(Object value, String field) {
        if (value == null || field == null) return null;
        try {
            Object raw = Reflect.get(value, field);
            return raw instanceof String ? (String) raw : null;
        } catch (Throwable ignored) { return null; }
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
        final String contentKey;

        Artifact(String path, String name, String mime, String contentKey) {
            this.path = path;
            this.name = name;
            this.mime = mime;
            this.contentKey = contentKey;
        }

        String displayName() {
            if (name != null && !name.isBlank()) return name;
            if (path != null && !path.isBlank()) return path;
            if (contentKey != null && !contentKey.isBlank()) return contentKey;
            return "下载文件";
        }

        /**
         * Source and legacy callbacks for one completed download must collapse to the same key.
         * Prefer normalized file identity; ContentId is only the fallback when no file metadata is
         * available yet.
         */
        String key() {
            if (path != null && !path.isBlank()) {
                return "file:" + path + '|' + String.valueOf(mime);
            }
            if (name != null && !name.isBlank()) {
                return "name:" + name + '|' + String.valueOf(mime);
            }
            if (contentKey != null && !contentKey.isBlank()) return "content:" + contentKey;
            return "unknown:" + String.valueOf(mime);
        }
    }

    private static final class OpenStamp {
        final String key;
        final long time;
        OpenStamp(String key, long time) { this.key = key; this.time = time; }
    }
}
