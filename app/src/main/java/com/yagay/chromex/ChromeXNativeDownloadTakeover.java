package com.yagay.chromex;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands reconstructible native Chrome downloads to ChromeX's own downloader.
 *
 * <p>The Chrome item is NOT cancelled immediately. ChromeXDownloadService first opens the same GET
 * request and only reports RESULT_ACCEPTED after receiving a successful HTTP response and opening
 * its MediaStore destination. Only then do we cancel/remove Chrome's native DownloadItem. If the
 * replacement cannot authenticate or otherwise fails before acceptance, Chrome continues normally.</p>
 */
final class ChromeXNativeDownloadTakeover {
    private static final String SERVICE =
            "org.chromium.chrome.browser.download.DownloadManagerService";
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final android.content.SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    ChromeXNativeDownloadTakeover(ChromeRuntime runtime, HookSupport hooks,
                                  android.content.SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        Class<?> service;
        try {
            service = Reflect.cls(runtime.classLoader, SERVICE);
        } catch (Throwable t) {
            hooks.warn("ChromeX downloader takeover unavailable: " + t.getClass().getSimpleName());
            return;
        }
        if (Reflect.named(service, "onDownloadItemCreated").isEmpty()) {
            hooks.warn("ChromeX downloader takeover: onDownloadItemCreated unresolved");
            return;
        }

        hooks.all(runtime.classLoader, SERVICE, "onDownloadItemCreated",
                "chromex:downloader:takeover-created", chain -> {
                    Object item = firstDownloadItem(chain.getArgs().toArray());
                    if (item != null) offer(chain.getThisObject(), item);
                    // Let Chrome publish/continue for now. It is cancelled only after ChromeX confirms
                    // that the replacement HTTP request is actually downloadable.
                    return chain.proceed();
                });
        hooks.info("ChromeX downloader takeover installed at DownloadManagerService.onDownloadItemCreated");
    }

    private void offer(Object service, Object item) {
        Object info = callOrNull(item, "getDownloadInfo");
        if (info == null) return;
        RequestValues values = readInfo(info);
        if (!values.usable() || !values.isGet) return;

        String guid = stringCall(item, "getId");
        if (guid == null || guid.isBlank() || !PENDING.add(guid)) return;
        Object contentId = callOrNull(item, "getContentId");
        Object otrProfileId = callOrNull(info, "getOtrProfileId");
        if (contentId == null) {
            PENDING.remove(guid);
            return;
        }

        ResultReceiver receiver = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                try {
                    if (resultCode == ChromeXDownloadService.RESULT_ACCEPTED) {
                        if (cancelNative(service, contentId, otrProfileId)) {
                            removeNativeRecord(service, guid, otrProfileId);
                            hooks.info("ChromeX downloader claimed Chrome task: guid=" + guid
                                    + " name=" + values.fileName);
                        } else {
                            hooks.warn("ChromeX downloader accepted but Chrome cancel failed: " + guid);
                        }
                    } else {
                        String reason = resultData == null ? null : resultData.getString("reason");
                        hooks.info("ChromeX downloader handed task back to Chrome: guid=" + guid
                                + " reason=" + reason);
                    }
                } finally {
                    PENDING.remove(guid);
                }
            }
        };

        Intent intent = new Intent(ChromeXDownloadService.ACTION_DOWNLOAD)
                .setComponent(new ComponentName("com.yagay.chromex",
                        "com.yagay.chromex.ChromeXDownloadService"))
                .putExtra(ChromeXDownloadService.EXTRA_URL, values.url)
                .putExtra(ChromeXDownloadService.EXTRA_NAME, values.fileName)
                .putExtra(ChromeXDownloadService.EXTRA_MIME, values.mime)
                .putExtra(ChromeXDownloadService.EXTRA_COOKIE, values.cookie)
                .putExtra(ChromeXDownloadService.EXTRA_REFERER, values.referrer)
                .putExtra(ChromeXDownloadService.EXTRA_USER_AGENT, values.userAgent)
                .putExtra(ChromeXDownloadService.EXTRA_GUID, guid)
                .putExtra(ChromeXDownloadService.EXTRA_RECEIVER, receiver);
        try {
            runtime.application.startForegroundService(intent);
            hooks.info("ChromeX downloader offered Chrome task: guid=" + guid
                    + " name=" + values.fileName
                    + " auth=" + ((values.cookie != null || values.userAgent != null) ? "metadata" : "limited"));
        } catch (Throwable t) {
            PENDING.remove(guid);
            hooks.warn("ChromeX downloader service start failed; Chrome keeps task: "
                    + t.getClass().getSimpleName());
        }
    }

    private boolean cancelNative(Object service, Object contentId, Object otrProfileId) {
        try {
            invokeCompatible(service, "cancelDownload", contentId, otrProfileId);
            return true;
        } catch (Throwable first) {
            hooks.warn("ChromeX downloader native cancel failed: " + first.getClass().getSimpleName());
            return false;
        }
    }

    private void removeNativeRecord(Object service, String guid, Object otrProfileId) {
        try { invokeCompatible(service, "removeDownload", guid, otrProfileId, Boolean.FALSE); }
        catch (Throwable ignored) {}
    }

    private Object firstDownloadItem(Object[] args) {
        if (args == null) return null;
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, ChromiumSemanticAnchors.DOWNLOAD_ITEM);
            for (Object arg : args) if (arg != null && type.isInstance(arg)) return arg;
        } catch (Throwable ignored) {}
        return null;
    }

    private RequestValues readInfo(Object info) {
        String url = urlSpec(callOrNull(info, "getUrl"));
        String fileName = stringCall(info, "getFileName");
        String mime = stringCall(info, "getMimeType");
        String cookie = stringCall(info, "getCookie");
        String referrer = urlSpec(callOrNull(info, "getReferrer"));
        String userAgent = stringCall(info, "getUserAgent");
        boolean isGet = booleanCall(info, "isGETRequest", true);
        if (fileName == null || mime == null) {
            DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, null);
            if (fileName == null) fileName = values.name;
            if (mime == null) mime = values.mime;
        }
        return new RequestValues(url, fileName, mime, cookie, referrer, userAgent, isGet);
    }

    private static Object callOrNull(Object owner, String method) {
        if (owner == null) return null;
        try { return Reflect.call(owner, method); }
        catch (Throwable ignored) { return null; }
    }

    private static String stringCall(Object owner, String method) {
        Object value = callOrNull(owner, method);
        return value instanceof String && !((String) value).isBlank() ? (String) value : null;
    }

    private static boolean booleanCall(Object owner, String method, boolean fallback) {
        Object value = callOrNull(owner, method);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static String urlSpec(Object value) {
        if (value == null) return null;
        if (value instanceof String) return ((String) value).isBlank() ? null : (String) value;
        try {
            Object spec = Reflect.call(value, "getSpec");
            return spec instanceof String && !((String) spec).isBlank() ? (String) spec : null;
        } catch (Throwable ignored) {
            String text = value.toString();
            return text.startsWith("http://") || text.startsWith("https://") ? text : null;
        }
    }

    private static Object invokeCompatible(Object owner, String name, Object... args) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] p = method.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (args[i] == null) continue;
                    if (!box(p[i]).isInstance(args[i])) { ok = false; break; }
                }
                if (!ok) continue;
                method.setAccessible(true);
                return method.invoke(owner, args);
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static final class RequestValues {
        final String url;
        final String fileName;
        final String mime;
        final String cookie;
        final String referrer;
        final String userAgent;
        final boolean isGet;

        RequestValues(String url, String fileName, String mime, String cookie,
                      String referrer, String userAgent, boolean isGet) {
            this.url = url;
            this.fileName = fileName;
            this.mime = mime;
            this.cookie = cookie;
            this.referrer = referrer;
            this.userAgent = userAgent;
            this.isGet = isGet;
        }

        boolean usable() {
            return url != null && fileName != null
                    && (url.startsWith("http://") || url.startsWith("https://"));
        }
    }
}
