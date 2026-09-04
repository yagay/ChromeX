package com.yagay.chromex;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;

final class BannerHooks {
    private static final long DOWNLOAD_BANNER_WINDOW_MS = 2500L;

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final AtomicLong suppressDownloadBannerUntil = new AtomicLong(0L);
    private final Handler main = new Handler(Looper.getMainLooper());

    BannerHooks(XposedModule module, HookSupport hooks,
                SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        hookDownloadCompletion();
        hookMessageDispatcher();
        hookTranslateMessage();
    }

    private void hookDownloadCompletion() {
        try {
            Class<?> item = Reflect.cls(loader, Chrome145.OFFLINE_ITEM);
            hooks.exact(loader, Chrome145.DOWNLOAD_MESSAGE, "d",
                    new Class<?>[]{item, boolean.class, boolean.class, boolean.class},
                    "chromex:banner:download", chain -> {
                        Object value = chain.getArg(0);
                        if (value != null) {
                            try {
                                int state = Reflect.getInt(value, "m0");
                                if (state == 2) {
                                    String mime = stringField(value, "f0");
                                    String title = stringField(value, "e0");
                                    String path = stringField(value, "P");
                                    boolean apk = InstallerHooks.isApk(mime, title != null ? title : path);
                                    boolean replace = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                                            || (apk && Config.get(prefs, Config.APK_TOAST));
                                    if (replace) {
                                        suppressDownloadBannerUntil.set(
                                                System.currentTimeMillis() + DOWNLOAD_BANNER_WINDOW_MS);
                                        showToast("下载完成: " + fileName(path, title));
                                    }
                                }
                            } catch (Throwable t) {
                                hooks.error("download banner detection", t);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            hooks.error("install download banner hook", t);
        }
    }

    private void hookMessageDispatcher() {
        try {
            Class<?> model = Reflect.cls(loader, Chrome145.PROPERTY_MODEL);
            hooks.exact(loader, Chrome145.MESSAGE_DISPATCHER, "c",
                    new Class<?>[]{model, boolean.class},
                    "chromex:banner:dispatch-c", chain -> {
                        long until = suppressDownloadBannerUntil.get();
                        if (until >= System.currentTimeMillis()) {
                            suppressDownloadBannerUntil.compareAndSet(until, 0L);
                            return null;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            hooks.error("install message-dispatcher hook", t);
        }
    }

    private void hookTranslateMessage() {
        try {
            Class<?> webContents = Reflect.cls(loader, Chrome145.WEB_CONTENTS);
            hooks.exact(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                    new Class<?>[]{webContents, long.class, int.class},
                    "chromex:banner:translate-create", chain -> {
                        if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                        return chain.proceed();
                    });

            hooks.exact(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                    new Class<?>[]{String.class, String.class, String.class, boolean.class},
                    "chromex:banner:translate-show", chain -> {
                        if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            hooks.error("install translation-banner hooks", t);
        }
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
