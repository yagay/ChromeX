package com.yagay.chromex;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

final class BannerHooks {
    private static final long DOWNLOAD_BANNER_WINDOW_MS = 3500L;
    private static final long TOAST_DEDUP_MS = 3000L;

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final AtomicLong suppressDownloadBannerUntil = new AtomicLong(0L);
    private final AtomicLong lastToastAt = new AtomicLong(0L);
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final Handler main = new Handler(Looper.getMainLooper());

    BannerHooks(XposedModule module, HookSupport hooks,
                SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        hookStableDownloadCompletion();
        hookCurrentMessageController();
        hookLegacyDownloadCompletion();
        hookLegacyMessageDispatcher();
        hookTranslateMessage();
    }

    private void hookStableDownloadCompletion() {
        // Install on both stable completion paths. The controller signature has changed between
        // releases, so locate DownloadInfo by type instead of using a fixed parameter list.
        hooks.all(loader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex:banner:controller-complete", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    beforeCompletion(info);
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.DOWNLOAD_MANAGER_SERVICE, "onDownloadCompleted",
                "chromex:banner:manager-complete", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    beforeCompletion(info);
                    return chain.proceed();
                });
    }

    private void beforeCompletion(Object info) {
        if (info == null) return;
        try {
            String mime = stringAccessor(info, "getMimeType", "c");
            String title = stringAccessor(info, "getFileName", "g");
            String path = stringAccessor(info, "getFilePath", "e");
            boolean apk = InstallerHooks.isApk(mime, title != null ? title : path);
            boolean replace = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                    || (apk && Config.get(prefs, Config.APK_TOAST));
            if (!replace) return;
            suppressDownloadBannerUntil.set(System.currentTimeMillis() + DOWNLOAD_BANNER_WINDOW_MS);
            showToastOnce(fileName(path, title));
        } catch (Throwable t) {
            hooks.error("stable download completion toast", t);
        }
    }

    private void hookCurrentMessageController() {
        // Current Chrome's completion UI is driven by DownloadMessageUiControllerImpl. We do not
        // inspect its obfuscated fields: a completion callback opens a short suppression window and
        // the next message update in that window is skipped.
        hooks.all(loader,
                "org.chromium.chrome.browser.download.DownloadMessageUiControllerImpl",
                "onItemUpdated", "chromex:banner:message-current", chain -> {
                    long until = suppressDownloadBannerUntil.get();
                    if (until >= System.currentTimeMillis()) {
                        suppressDownloadBannerUntil.compareAndSet(until, 0L);
                        hooks.info("current download completion message suppressed");
                        return null;
                    }
                    return chain.proceed();
                });
    }

    private void hookLegacyDownloadCompletion() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            hooks.exact(loader, Chrome145.DOWNLOAD_MESSAGE, "d",
                    new Class<?>[]{item, boolean.class, boolean.class, boolean.class},
                    "chromex:banner:download:145", chain -> {
                        Object value = chain.getArg(0);
                        if (value != null) {
                            try {
                                int state = Reflect.getInt(value, "m0");
                                if (state == 2) {
                                    String mime = stringField(value, "f0");
                                    String title = stringField(value, "e0");
                                    String path = stringField(value, "P");
                                    boolean apk = InstallerHooks.isApk(
                                            mime, title != null ? title : path);
                                    boolean replace = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                                            || (apk && Config.get(prefs, Config.APK_TOAST));
                                    if (replace) {
                                        suppressDownloadBannerUntil.set(
                                                System.currentTimeMillis() + DOWNLOAD_BANNER_WINDOW_MS);
                                        showToastOnce(fileName(path, title));
                                    }
                                }
                            } catch (Throwable t) {
                                hooks.warn("legacy download banner path unavailable: "
                                        + t.getClass().getSimpleName());
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            hooks.warn("legacy download banner hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void hookLegacyMessageDispatcher() {
        try {
            Class<?> model = Reflect.cls(loader, Chrome145.PROPERTY_MODEL);
            hooks.exact(loader, Chrome145.MESSAGE_DISPATCHER, "c",
                    new Class<?>[]{model, boolean.class},
                    "chromex:banner:dispatch:145", chain -> {
                        long until = suppressDownloadBannerUntil.get();
                        if (until >= System.currentTimeMillis()) {
                            suppressDownloadBannerUntil.compareAndSet(until, 0L);
                            return null;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            hooks.warn("legacy message-dispatcher hook unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private void hookTranslateMessage() {
        // Method names are part of the non-obfuscated Chromium bridge API. Hook all overloads so a
        // harmless signature extension does not disable the feature.
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex:banner:translate-create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex:banner:translate-show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> infoType = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && infoType.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void showToastOnce(String name) {
        long now = System.currentTimeMillis();
        String previousName = lastToastName.get();
        long previousTime = lastToastAt.get();
        if (name.equals(previousName) && now - previousTime < TOAST_DEDUP_MS) return;
        lastToastName.set(name);
        lastToastAt.set(now);
        showToast("下载完成: " + name);
    }

    private void showToast(String message) {
        Context context = chromeContext();
        if (context == null) return;
        main.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                hooks.error("show download toast", t);
            }
        });
    }

    private Context chromeContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringAccessor(Object value, String getter, String legacyField) {
        if (value == null) return null;
        try {
            Object result = Reflect.call(value, getter);
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return stringField(value, legacyField);
    }

    private static String stringField(Object value, String field) {
        try {
            Object result = Reflect.get(value, field);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String fileName(String path, String fallback) {
        String value = path;
        if (value == null || value.isBlank()) value = fallback;
        if (value == null || value.isBlank()) return "下载文件";
        try {
            if (!value.startsWith("content://")) value = new File(value).getName();
        } catch (Throwable ignored) {}
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        return value.isBlank() ? "下载文件" : value;
    }
}
