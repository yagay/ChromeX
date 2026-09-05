package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Verified download implementation for Chrome 152.0.7977.75. */
final class Chrome152DownloadHooks {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long BANNER_WINDOW_MS = 3500L;
    private static final long TOAST_DEDUP_MS = 3000L;
    private static final long INSTALL_DEDUP_MS = 90_000L;
    private static final long INSTALL_WAIT_MS = 20_000L;
    private static final long INSTALL_POLL_MS = 500L;
    private static final long COMPLETION_SETTLE_MS = 750L;

    private final ClassLoader loader;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong suppressBannerUntil = new AtomicLong();
    private final AtomicInteger suppressBudget = new AtomicInteger();
    private final AtomicLong lastToastAt = new AtomicLong();
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final AtomicReference<InstallStamp> lastInstall = new AtomicReference<>();
    private final ExecutorService installerWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChromeX-152-installer");
        t.setDaemon(true);
        return t;
    });

    Chrome152DownloadHooks(ClassLoader loader, HookSupport hooks, SharedPreferences prefs) {
        this.loader = loader;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        installDialogs();
        installCompletion();
        installBannerSuppression();
        installTranslateSuppression();
    }

    private void installDialogs() {
        hookDangerous();
        hookInsecure();
        hookDuplicate();
        hookPolicy();
        hookLocation();
        hookOpen();
    }

    private void hookDangerous() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                "showDialog", "chromex152:download:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)
                            || chain.getArgs().size() < 2
                            || !(chain.getArg(1) instanceof String)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                Chrome152.DANGEROUS_ACCEPT, ptr, chain.getArg(1));
                        hooks.info("Chrome 152 dangerous download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 dangerous confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookInsecure() {
        hooks.all(loader, "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex152:download:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)
                            || chain.getArgs().size() < 4
                            || !(chain.getArg(3) instanceof Number)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJJZ", new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                Chrome152.INSECURE_ACCEPT, ptr,
                                ((Number) chain.getArg(3)).longValue(), true);
                        hooks.info("Chrome 152 insecure download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 insecure confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookDuplicate() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex152:download:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object last = chain.getArg(chain.getArgs().size() - 1);
                    if (!(last instanceof Number)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJJZ", new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                Chrome152.DUPLICATE_ACCEPT, ptr, ((Number) last).longValue(), true);
                        hooks.info("Chrome 152 duplicate download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 duplicate confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookPolicy() {
        hooks.all(loader, "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex152:download:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)
                            || chain.getArgs().size() < 2
                            || !(chain.getArg(1) instanceof String)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                Chrome152.POLICY_ACCEPT, ptr, chain.getArg(1));
                        hooks.info("Chrome 152 policy warning confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 policy confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookLocation() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex152:download:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) path = "";
                    try {
                        Reflect.call(chain.getThisObject(), "b", path, Boolean.FALSE);
                        hooks.info("Chrome 152 download location accepted automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 location confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookOpen() {
        hooks.all(loader, "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex152:download:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJOZ", new Class<?>[]{int.class, long.class, String.class, boolean.class},
                                Chrome152.OPEN_ACCEPT, ptr, path, true);
                        hooks.info("Chrome 152 open-file confirmation accepted automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 open-file confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void installCompletion() {
        hooks.all(loader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex152:download:completed", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (info != null) main.postDelayed(() -> handleCompletion(info), COMPLETION_SETTLE_MS);
                    return result;
                });

        hooks.all(loader, Chrome145.DOWNLOAD_UTILS, "openDownload",
                "chromex152:download:open-apk", chain -> {
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
                            chromeContext(), loader, path, fileName);
                    if (resolved.uri != null && launchInstallerNow(resolved.uri, fileName)) return null;
                    if (resolved.terminal) hooks.warn("Chrome 152 APK open blocked safely: " + resolved.detail);
                    return chain.proceed();
                });
    }

    private void handleCompletion(Object info) {
        try {
            String mime = stringField(info, Chrome152.DOWNLOAD_INFO_MIME);
            String name = stringField(info, Chrome152.DOWNLOAD_INFO_NAME);
            String path = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);
            String logical = DownloadNormalizationRegistry.logicalPath(path);
            if (logical != null) {
                path = logical;
                name = new File(logical).getName();
            }

            boolean apk = isApk(mime, name != null ? name : path);
            boolean replaceBanner = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                    || (apk && Config.get(prefs, Config.APK_TOAST));
            if (replaceBanner) {
                suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
                suppressBudget.set(4);
                showToastOnce(fileName(path, name));
            }
            if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) enqueueInstall(path, name);
        } catch (Throwable t) {
            hooks.error("Chrome 152 download completion", t);
        }
    }

    private void installBannerSuppression() {
        for (String method : new String[]{"a", "d"}) {
            hooks.all(loader, Chrome152.DOWNLOAD_MESSAGE, method,
                    "chromex152:banner:message-" + method, chain -> {
                        if (suppressBannerUntil.get() >= System.currentTimeMillis()
                                && takeSuppressionBudget()) return null;
                        return chain.proceed();
                    });
        }
    }

    private boolean takeSuppressionBudget() {
        while (true) {
            int value = suppressBudget.get();
            if (value <= 0) return false;
            if (suppressBudget.compareAndSet(value, value - 1)) return true;
        }
    }

    private void installTranslateSuppression() {
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex152:translate:create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex152:translate:show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
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
                        chromeContext(), loader, candidatePath, candidateName);
                if (resolved.uri != null) {
                    Uri uri = resolved.uri;
                    main.post(() -> launchInstallerNow(uri, candidateName));
                    return;
                }
                if (resolved.terminal) {
                    hooks.warn("Chrome 152 APK installer stopped: " + candidateName
                            + " :: " + resolved.detail);
                    return;
                }
                try { Thread.sleep(INSTALL_POLL_MS); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            hooks.warn("Chrome 152 APK not resolvable after 20s: " + fileName);
        });
    }

    private boolean launchInstallerNow(Uri uri, String key) {
        if (!InstallerUriResolver.isContent(uri)) {
            hooks.warn("Chrome 152 refused non-content installer URI");
            return false;
        }
        long now = System.currentTimeMillis();
        String safeKey = key == null ? uri.toString() : key;
        InstallStamp previous = lastInstall.get();
        if (previous != null && previous.key.equals(safeKey)
                && now - previous.time < INSTALL_DEDUP_MS) return true;
        Context context = chromeContext();
        if (context == null) return false;
        InstallStamp next = new InstallStamp(safeKey, now);
        lastInstall.set(next);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            hooks.info("Chrome 152 APK installer launched: " + safeKey
                    + " uri=" + uri.getAuthority());
            return true;
        } catch (Throwable t) {
            lastInstall.compareAndSet(next, previous);
            hooks.error("Chrome 152 launch APK installer", t);
            return false;
        }
    }

    private void showToastOnce(String name) {
        long now = System.currentTimeMillis();
        String safe = name == null || name.isBlank() ? "下载文件" : name;
        if (safe.equals(lastToastName.get()) && now - lastToastAt.get() < TOAST_DEDUP_MS) return;
        lastToastName.set(safe);
        lastToastAt.set(now);
        Context context = chromeContext();
        if (context == null) return;
        main.post(() -> {
            try { Toast.makeText(context, "下载完成: " + safe, Toast.LENGTH_SHORT).show(); }
            catch (Throwable t) { hooks.error("Chrome 152 download Toast", t); }
        });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
        } catch (Throwable ignored) {}
        return null;
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            long value = Reflect.getLong(bridge, "a");
            if (value != 0L) return value;
        } catch (Throwable ignored) {}
        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (value == 0L) continue;
                    if (found != null) return 0L;
                    found = field;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try { return found.getLong(bridge); } catch (Throwable ignored) { return 0L; }
    }

    private Object nativeCall(String name, Class<?>[] params, Object... args)
            throws ReflectiveOperationException {
        Method method = Reflect.exact(Reflect.cls(loader, Chrome145.NATIVE), name, params);
        return method.invoke(null, args);
    }

    private Context chromeContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) { return null; }
    }

    private static String stringField(Object object, String field) {
        if (object == null) return null;
        try {
            Object value = Reflect.get(object, field);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) if (args[i] instanceof String) return (String) args[i];
        return null;
    }

    private static boolean isApk(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("package-archive")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static String normalizedName(String name, String path) {
        if (name != null && !name.isBlank()) return new File(name).getName();
        if (path != null && !path.isBlank()) return new File(path).getName();
        return null;
    }

    private static String fileName(String path, String name) {
        String result = normalizedName(name, path);
        return result == null ? "下载文件" : result;
    }

    private static final class InstallStamp {
        final String key;
        final long time;
        InstallStamp(String key, long time) { this.key = key; this.time = time; }
    }
}
