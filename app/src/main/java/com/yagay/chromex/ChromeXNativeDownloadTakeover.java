package com.yagay.chromex;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes ownership of downloads created by Chromium's native download stack.
 *
 * <p>Standard Chrome downloads are normally created in native code and only then mirrored into
 * Java through DownloadManagerService.onDownloadItemCreated(). That is the first stable Java
 * boundary where ChromeX has both the real DownloadItem/GUID and the full DownloadInfo. ChromeX
 * cancels the native item there and re-enqueues the request through Android DownloadManager.</p>
 */
final class ChromeXNativeDownloadTakeover {
    private static final String SERVICE =
            "org.chromium.chrome.browser.download.DownloadManagerService";
    private static final Set<String> CLAIMED = ConcurrentHashMap.newKeySet();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    ChromeXNativeDownloadTakeover(ChromeRuntime runtime, HookSupport hooks,
                                  SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        Class<?> service;
        try {
            service = Reflect.cls(runtime.classLoader, SERVICE);
        } catch (Throwable t) {
            hooks.warn("ChromeX native takeover unavailable: " + t.getClass().getSimpleName());
            return;
        }

        if (Reflect.named(service, "onDownloadItemCreated").isEmpty()) {
            hooks.warn("ChromeX native takeover: onDownloadItemCreated unresolved");
            return;
        }

        hooks.all(runtime.classLoader, SERVICE, "onDownloadItemCreated",
                "chromex:native-download:takeover-created", chain -> {
                    Object item = firstDownloadItem(chain.getArgs().toArray());
                    if (item == null) return chain.proceed();
                    if (!takeOver(chain.getThisObject(), item)) return chain.proceed();
                    // Do not publish the now-cancelled native item into Chrome's Java download UI.
                    return null;
                });
        hooks.info("ChromeX native download takeover installed at DownloadManagerService.onDownloadItemCreated");
    }

    private boolean takeOver(Object service, Object item) {
        Object info;
        try { info = Reflect.call(item, "getDownloadInfo"); }
        catch (Throwable t) { return false; }
        if (info == null) return false;

        RequestValues values = readInfo(info);
        if (!values.usable()) {
            hooks.warn("ChromeX native takeover skipped unresolved DownloadInfo");
            return false;
        }
        if (!values.isGet) {
            hooks.warn("ChromeX native takeover skipped non-GET download: " + values.fileName);
            return false;
        }

        String guid = stringCall(item, "getId");
        if (guid == null || guid.isBlank() || !CLAIMED.add(guid)) return false;

        Object contentId = callOrNull(item, "getContentId");
        Object otrProfileId = callOrNull(info, "getOtrProfileId");
        if (contentId == null || !cancelNative(service, contentId, otrProfileId)) {
            CLAIMED.remove(guid);
            hooks.warn("ChromeX native takeover could not cancel native item: " + guid);
            return false;
        }

        // Removing the cancelled native record prevents it from reappearing in Chrome's history.
        removeNativeRecord(service, guid, otrProfileId);
        enqueue(values, guid);
        hooks.info("ChromeX native download claimed: guid=" + guid
                + " name=" + values.fileName + " url=" + values.url);
        return true;
    }

    private boolean cancelNative(Object service, Object contentId, Object otrProfileId) {
        try {
            invokeCompatible(service, "cancelDownload", contentId, otrProfileId);
            return true;
        } catch (Throwable first) {
            hooks.warn("ChromeX native cancel failed: " + first.getClass().getSimpleName());
            return false;
        }
    }

    private void removeNativeRecord(Object service, String guid, Object otrProfileId) {
        try { invokeCompatible(service, "removeDownload", guid, otrProfileId, Boolean.FALSE); }
        catch (Throwable ignored) {}
    }

    private void enqueue(RequestValues values, String guid) {
        Thread worker = new Thread(() -> {
            long id = -1L;
            try {
                Uri uri = Uri.parse(values.url);
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("unsupported scheme " + scheme);
                }

                DownloadManager.Request request = new DownloadManager.Request(uri);
                if (values.mime != null) request.setMimeType(values.mime);
                if (values.fileName != null) {
                    request.setTitle(values.fileName);
                    request.setDescription(values.description == null
                            ? values.fileName : values.description);
                }
                addHeader(request, "Cookie", values.cookie);
                addHeader(request, "Referer", values.referrer);
                addHeader(request, "User-Agent", values.userAgent);

                String name = safeFileName(values.fileName);
                if (name == null) throw new IllegalStateException("filename missing");
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File target = new File(dir, name).getCanonicalFile();
                if (!sameDirectory(dir, target)) throw new IllegalArgumentException("unsafe filename");

                if (Config.get(prefs, Config.OVERWRITE_DUPLICATE) && target.exists()) {
                    if (target.isDirectory() || !target.delete()) {
                        throw new IllegalStateException("cannot replace existing target");
                    }
                }

                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                DownloadManager manager = (DownloadManager) runtime.application
                        .getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                id = manager.enqueue(request);
                hooks.info("ChromeX replacement download started: guid=" + guid
                        + " systemId=" + id + " target=" + target.getAbsolutePath());
            } catch (Throwable t) {
                hooks.error("ChromeX replacement download enqueue failed: " + values.fileName, t);
            }
        }, "ChromeX-native-download");
        worker.setDaemon(true);
        worker.start();
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
        String description = stringCall(info, "getDescription");
        String mime = stringCall(info, "getMimeType");
        String cookie = stringCall(info, "getCookie");
        String referrer = urlSpec(callOrNull(info, "getReferrer"));
        String userAgent = stringCall(info, "getUserAgent");
        boolean isGet = booleanCall(info, "isGETRequest", true);

        // Exact/structural fallback for obfuscated builds.
        if (fileName == null || mime == null) {
            DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, null);
            if (fileName == null) fileName = values.name;
            if (mime == null) mime = values.mime;
        }
        return new RequestValues(url, fileName, description, mime, cookie, referrer, userAgent, isGet);
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
                    Class<?> boxed = box(p[i]);
                    if (!boxed.isInstance(args[i])) { ok = false; break; }
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

    private static void addHeader(DownloadManager.Request request, String name, String value) {
        if (value == null || value.isBlank()) return;
        try { request.addRequestHeader(name, value); } catch (Throwable ignored) {}
    }

    private static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = DownloadNamePolicy.fileNameOnly(raw);
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    private static boolean sameDirectory(File directory, File target) {
        try {
            File dir = directory.getCanonicalFile();
            File parent = target.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(dir);
        } catch (Throwable ignored) { return false; }
    }

    private static final class RequestValues {
        final String url;
        final String fileName;
        final String description;
        final String mime;
        final String cookie;
        final String referrer;
        final String userAgent;
        final boolean isGet;

        RequestValues(String url, String fileName, String description, String mime,
                      String cookie, String referrer, String userAgent, boolean isGet) {
            this.url = url;
            this.fileName = fileName;
            this.description = description;
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
