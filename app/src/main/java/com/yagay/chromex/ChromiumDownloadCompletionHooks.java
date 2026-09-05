package com.yagay.chromex;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Shared completion/Toast/APK-installer/banner/translate feature for verified Chromium profiles. */
final class ChromiumDownloadCompletionHooks {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long COMPLETION_SETTLE_MS = 750L;
    private static final long COMPLETION_DEDUP_MS = 3000L;
    private static final long BANNER_WINDOW_MS = 3500L;
    private static final long TOAST_DEDUP_MS = 3000L;
    private static final long INSTALL_DEDUP_MS = 90_000L;
    private static final long INSTALL_WAIT_MS = 20_000L;
    private static final long INSTALL_POLL_MS = 500L;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> completions = new ConcurrentHashMap<>();
    private final AtomicLong suppressBannerUntil = new AtomicLong();
    private final AtomicInteger suppressBudget = new AtomicInteger();
    private final AtomicLong lastToastAt = new AtomicLong();
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final AtomicReference<InstallStamp> lastInstall = new AtomicReference<>();
    private final ExecutorService installerWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ChromeX-installer");
        thread.setDaemon(true);
        return thread;
    });

    ChromiumDownloadCompletionHooks(ChromiumProfile profile, ChromeRuntime runtime,
                                    HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.loader = runtime.classLoader;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installStableCompletion();
        installOpenDownload();
        installBannerSuppression();
        installTranslateSuppression();
        if (profile.is145()) install145Fallbacks();
    }

    private void installStableCompletion() {
        hookCompletionOwner(Chrome145.DOWNLOAD_CONTROLLER,
                "chromex:download:controller-completed");
        hookCompletionOwner(Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "chromex:download:manager-completed");
    }

    private void hookCompletionOwner(String owner, String id) {
        hooks.all(loader, owner, "onDownloadCompleted", id, chain -> {
            Object info = findDownloadInfo(chain.getArgs().toArray());
            Object result = chain.proceed();
            if (info != null) main.postDelayed(() -> handleCompletion(info, owner), COMPLETION_SETTLE_MS);
            return result;
        });
    }

    private void handleCompletion(Object info, String source) {
        try {
            DownloadArtifact artifact = artifact(info);
            if (artifact == null || !markCompletion(artifact.key())) return;
            boolean apk = isApk(artifact.mime, artifact.name != null ? artifact.name : artifact.path);
            boolean replaceBanner = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                    || (apk && Config.get(prefs, Config.APK_TOAST));
            if (replaceBanner) {
                suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
                suppressBudget.set(4);
                showToastOnce(artifact.displayName());
            }
            if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) {
                enqueueInstall(artifact.path, artifact.name);
            }
            hooks.info("download completion normalized through shared pipeline: "
                    + artifact.displayName() + " source=" + simpleName(source));
        } catch (Throwable t) {
            hooks.error("shared download completion", t);
        }
    }

    private DownloadArtifact artifact(Object info) {
        if (info == null) return null;
        String mime = stringAccessor(info, "getMimeType", "c");
        String path = stringAccessor(info, "getFilePath", profile.is152()
                ? Chrome152.DOWNLOAD_INFO_PATH : "e");
        String name = stringAccessor(info, "getFileName", profile.is152()
                ? Chrome152.DOWNLOAD_INFO_NAME : "g");
        String logical = DownloadNormalizationRegistry.logicalPath(path);
        if (logical != null) {
            path = logical;
            name = new File(logical).getName();
        }
        if ((path == null || path.isBlank()) && (name == null || name.isBlank())) return null;
        return new DownloadArtifact(path, name, mime);
    }

    private void installOpenDownload() {
        hooks.all(loader, Chrome145.DOWNLOAD_UTILS, "openDownload",
                "chromex:download:open-apk", chain -> {
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (args.length < 2) return chain.proceed();
                    String path = string(args[0]);
                    String mime = string(args[1]);
                    String name = string(args[args.length - 1]);
                    String logical = DownloadNormalizationRegistry.logicalPath(path);
                    if (logical != null) {
                        path = logical;
                        name = new File(logical).getName();
                    }
                    if (!isApk(mime, name != null ? name : path)) return chain.proceed();
                    String fileName = normalizedName(name, path);
                    InstallerUriResolver.Result resolved = InstallerUriResolver.resolve(
                            runtime.application, loader, path, fileName);
                    if (resolved.uri != null && launchInstallerNow(resolved.uri, fileName)) return null;
                    if (resolved.terminal) {
                        hooks.warn(profile.label() + " APK open blocked safely: " + resolved.detail);
                    }
                    return chain.proceed();
                });
    }

    private void installBannerSuppression() {
        hooks.all(loader,
                "org.chromium.chrome.browser.download.DownloadMessageUiControllerImpl",
                "onItemUpdated", "chromex:banner:message-current", chain -> {
                    if (takeBannerSuppression(false)) return null;
                    return chain.proceed();
                });

        if (profile.is152()) {
            for (String method : new String[]{"a", "d"}) {
                hooks.all(loader, Chrome152.DOWNLOAD_MESSAGE, method,
                        "chromex:banner:152:" + method, chain -> {
                            if (takeBannerSuppression(true)) return null;
                            return chain.proceed();
                        });
            }
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

    private void installTranslateSuppression() {
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex:translate:create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex:translate:show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
    }

    private void install145Fallbacks() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            Class<?> visuals = Reflect.cls(loader, Chrome145.OFFLINE_VISUALS);
            hooks.exact(loader, Chrome145.OFFLINE_COMPLETE, "f",
                    new Class<?>[]{item, visuals}, "chromex:download:offline145", chain -> {
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

        hooks.exact(loader, Chrome145.DOWNLOAD_EVENT_RUNNABLE, "run", new Class<?>[0],
                "chromex:download:event145", chain -> {
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

        try {
            Class<?> model = Reflect.cls(loader, Chrome145.PROPERTY_MODEL);
            hooks.exact(loader, Chrome145.MESSAGE_DISPATCHER, "c",
                    new Class<?>[]{model, boolean.class}, "chromex:banner:dispatch145", chain -> {
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
        DownloadArtifact artifact = new DownloadArtifact(path, name, mime);
        if (!markCompletion(artifact.key())) return;
        boolean apk = isApk(mime, name != null ? name : path);
        boolean replaceBanner = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                || (apk && Config.get(prefs, Config.APK_TOAST));
        if (replaceBanner) {
            suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
            suppressBudget.set(4);
            showToastOnce(artifact.displayName());
        }
        if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) enqueueInstall(path, name);
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

    private void enqueueInstall(String path, String name) {
        String fileName = normalizedName(name, path);
        if (fileName == null) return;
        installerWorker.execute(() -> {
            long deadline = System.currentTimeMillis() + INSTALL_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return;
                String logical = DownloadNormalizationRegistry.logicalPath(path);
                String candidatePath = logical == null ? path : logical;
                String candidateName = logical == null ? fileName : new File(logical).getName();
                InstallerUriResolver.Result resolved = InstallerUriResolver.resolve(
                        runtime.application, loader, candidatePath, candidateName);
                if (resolved.uri != null) {
                    Uri uri = resolved.uri;
                    main.post(() -> launchInstallerNow(uri, candidateName));
                    return;
                }
                if (resolved.terminal) {
                    hooks.warn(profile.label() + " APK installer stopped: " + candidateName
                            + " :: " + resolved.detail);
                    return;
                }
                try {
                    Thread.sleep(INSTALL_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            hooks.warn(profile.label() + " APK not resolvable after 20s: " + fileName);
        });
    }

    private boolean launchInstallerNow(Uri uri, String key) {
        if (!InstallerUriResolver.isContent(uri)) return false;
        long now = System.currentTimeMillis();
        String safeKey = key == null ? uri.toString() : key;
        InstallStamp previous = lastInstall.get();
        if (previous != null && previous.key.equals(safeKey)
                && now - previous.time < INSTALL_DEDUP_MS) return true;
        InstallStamp next = new InstallStamp(safeKey, now);
        lastInstall.set(next);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            runtime.application.startActivity(intent);
            hooks.info(profile.label() + " APK installer launched: " + safeKey);
            return true;
        } catch (Throwable t) {
            lastInstall.compareAndSet(next, previous);
            hooks.error(profile.label() + " launch APK installer", t);
            return false;
        }
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
                hooks.error("show download toast", t);
            }
        });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String stringAccessor(Object value, String getter, String fallbackField) {
        if (value == null) return null;
        try {
            Object result = Reflect.call(value, getter);
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return stringField(value, fallbackField);
    }

    private static String stringField(Object value, String field) {
        if (value == null || field == null) return null;
        try {
            Object result = Reflect.get(value, field);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean isApk(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("package-archive")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static String normalizedName(String name, String path) {
        String value = name;
        if (value == null || value.isBlank()) value = path;
        if (value == null || value.isBlank()) return null;
        try { return new File(value).getName(); }
        catch (Throwable ignored) { return value; }
    }

    private static String simpleName(String value) {
        if (value == null) return "unknown";
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
    }

    private static final class DownloadArtifact {
        final String path;
        final String name;
        final String mime;

        DownloadArtifact(String path, String name, String mime) {
            this.path = path;
            this.name = name;
            this.mime = mime;
        }

        String displayName() {
            String value = normalizedName(name, path);
            return value == null ? "下载文件" : value;
        }

        String key() {
            if (path != null && !path.isBlank()) return path;
            if (name != null && !name.isBlank()) return name;
            return String.valueOf(mime);
        }
    }

    private static final class InstallStamp {
        final String key;
        final long time;
        InstallStamp(String key, long time) {
            this.key = key;
            this.time = time;
        }
    }
}
