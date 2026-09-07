package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * Controls Chromium's Android DownloadCollection duplicate handling.
 *
 * <p>There are two independent name-conflict layers on Android. Chromium first probes
 * DownloadCollectionBridge.fileNameExists(), while MediaProvider can still uniquify the physical
 * file when createIntermediateUriForPublish() inserts a pending Downloads row. We therefore keep
 * Chromium on the requested logical name and, only after publish succeeds, replace the old
 * same-name MediaStore item and rename the newly published Uri back to the requested name.</p>
 *
 * <p>The old item is deliberately kept until the new download has published successfully, so a
 * failed/interrupted replacement download never destroys the existing file.</p>
 */
final class DownloadCollectionConflictHooks {
    private static final String BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";

    /** intermediate/published content Uri -> originally requested display name. */
    private static final Map<String, String> REQUESTED_NAMES = new ConcurrentHashMap<>();

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    DownloadCollectionConflictHooks(ChromeRuntime runtime, HookSupport hooks,
                                    SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        Class<?> bridge;
        try {
            bridge = Reflect.cls(runtime.classLoader, BRIDGE);
            if (Reflect.named(bridge, "fileNameExists").isEmpty()) {
                hooks.warn("DownloadCollectionBridge.fileNameExists unresolved");
                return;
            }
        } catch (Throwable t) {
            hooks.warn("DownloadCollectionBridge unavailable: " + t.getClass().getSimpleName());
            return;
        }

        installExistsBypass();
        installIntermediateCapture();
        installPublishReplacement(bridge);
        hooks.info("ChromeX DownloadCollection overwrite pipeline installed");
    }

    private void installExistsBypass() {
        hooks.all(runtime.classLoader, BRIDGE, "fileNameExists",
                "chromex:download-collection:file-name-exists", chain -> {
                    if (!enabled()) return chain.proceed();
                    String name = stringArg(chain, 0);
                    hooks.info("ChromeX DownloadCollection duplicate bypass: "
                            + (name == null ? "<unknown>" : name));
                    return false;
                });
    }

    /** Capture the requested name together with the concrete pending content Uri. */
    private void installIntermediateCapture() {
        hooks.all(runtime.classLoader, BRIDGE, "createIntermediateUriForPublish",
                "chromex:download-collection:remember-pending-name", chain -> {
                    String requested = stringArg(chain, 0);
                    Object result = chain.proceed();
                    if (!enabled() || requested == null || !(result instanceof String)) return result;
                    String uri = (String) result;
                    if (!uri.isBlank()) {
                        REQUESTED_NAMES.put(uri, requested);
                        hooks.info("ChromeX DownloadCollection pending mapped: "
                                + requested + " -> " + uri);
                    }
                    return result;
                });
    }

    /**
     * The physical filename may already have been uniquified by MediaProvider at pending-row
     * creation. Wait until publish succeeds, then remove the previous exact-name item and ask
     * DownloadCollectionBridge to rename this newly completed Uri back to the requested name.
     */
    private void installPublishReplacement(Class<?> bridge) {
        hooks.all(runtime.classLoader, BRIDGE, "publishDownload",
                "chromex:download-collection:replace-after-publish", chain -> {
                    String intermediate = stringArg(chain, 0);
                    Object result = chain.proceed();
                    if (!enabled() || intermediate == null || !(result instanceof String)) return result;

                    String published = (String) result;
                    String requested = REQUESTED_NAMES.remove(intermediate);
                    if (requested == null && published != null) {
                        requested = REQUESTED_NAMES.remove(published);
                    }
                    if (requested == null || published == null || published.isBlank()) return result;

                    try {
                        int removed = deletePreviousExactName(requested, published);
                        boolean renamed = renamePublishedUri(bridge, published, requested);
                        hooks.info("ChromeX DownloadCollection publish replace: name=" + requested
                                + " removed=" + removed + " renamed=" + renamed
                                + " uri=" + published);
                    } catch (Throwable t) {
                        hooks.error("ChromeX DownloadCollection publish replace failed: " + requested, t);
                    }
                    return result;
                });
    }

    private int deletePreviousExactName(String displayName, String keepUri) {
        ContentResolver resolver = runtime.application.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        int removed = 0;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(collection, projection,
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{displayName}, null)) {
            if (cursor == null) return 0;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri candidate = Uri.withAppendedPath(collection, Long.toString(id));
                if (sameUri(candidate.toString(), keepUri)) continue;
                try {
                    removed += resolver.delete(candidate, null, null);
                } catch (Throwable t) {
                    hooks.warn("ChromeX could not delete previous MediaStore item "
                            + candidate + ": " + t.getClass().getSimpleName());
                }
            }
        }
        return removed;
    }

    private boolean renamePublishedUri(Class<?> bridge, String publishedUri, String displayName)
            throws Exception {
        for (Method method : bridge.getDeclaredMethods()) {
            if (!method.getName().equals("renameDownloadUri")
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 2) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p[0] != String.class || p[1] != String.class) continue;
            method.setAccessible(true);
            Object value = method.invoke(null, publishedUri, displayName);
            return value instanceof Boolean && (Boolean) value;
        }
        throw new NoSuchMethodException("renameDownloadUri(String,String)");
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private static String stringArg(XposedInterface.Chain chain, int index) {
        try {
            if (chain.getArgs().size() <= index) return null;
            Object value = chain.getArg(index);
            return value instanceof String && !((String) value).isBlank() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameUri(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try { return Uri.parse(a).normalizeScheme().equals(Uri.parse(b).normalizeScheme()); }
        catch (Throwable ignored) { return false; }
    }
}
