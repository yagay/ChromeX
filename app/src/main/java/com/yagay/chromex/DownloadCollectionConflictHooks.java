package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * Controls Chromium's Android DownloadCollection duplicate handling.
 *
 * <p>Chromium can keep the requested logical name while MediaProvider still uniquifies the real
 * file at pending-row creation. After publish succeeds, ChromeX resolves the new item's physical
 * path, asks ChromeX's own process to remove only the old sibling target through root, then lets
 * Chromium's own renameDownloadUri() move the new file and update MediaStore consistently.</p>
 *
 * <p>The root operation intentionally runs through RootBridgeProvider. KernelSU's su visibility is
 * available to ChromeX itself but not necessarily to the hooked Chrome process.</p>
 */
final class DownloadCollectionConflictHooks {
    private static final String BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";
    private static final Uri ROOT_BRIDGE_URI =
            Uri.parse("content://" + RootBridgeProvider.AUTHORITY);

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

    private void installIntermediateCapture() {
        hooks.all(runtime.classLoader, BRIDGE, "createIntermediateUriForPublish",
                "chromex:download-collection:remember-pending-name", chain -> {
                    String requested = safeName(stringArg(chain, 0));
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
                    requested = safeName(requested);
                    if (requested == null || published == null || published.isBlank()) return result;

                    try {
                        PhysicalItem physical = resolvePhysicalItem(published);
                        RootDeleteResult root = freeRequestedSibling(physical, requested);
                        boolean renamed = renamePublishedUri(bridge, published, requested);
                        int staleRows = deletePreviousExactName(requested, published);
                        hooks.info("ChromeX DownloadCollection physical replace: name=" + requested
                                + " actual=" + (physical.path == null ? "<unknown>" : physical.path)
                                + " display=" + (physical.displayName == null ? "<unknown>" : physical.displayName)
                                + " rootTarget=" + (root.target == null ? "<none>" : root.target)
                                + " su=" + (root.suPath == null ? "<none>" : root.suPath)
                                + " rootDeleted=" + root.deleted
                                + " rootExit=" + root.exitCode
                                + " rootReason=" + (root.reason == null ? "<none>" : root.reason)
                                + " renamed=" + renamed
                                + " staleRows=" + staleRows
                                + " uri=" + published);
                    } catch (Throwable t) {
                        hooks.error("ChromeX DownloadCollection physical replace failed: " + requested, t);
                    }
                    return result;
                });
    }

    private PhysicalItem resolvePhysicalItem(String publishedUri) {
        Uri uri = Uri.parse(publishedUri);
        ContentResolver resolver = runtime.application.getContentResolver();
        String[] projection = {
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String data = columnString(cursor, MediaStore.MediaColumns.DATA);
                String display = columnString(cursor, MediaStore.MediaColumns.DISPLAY_NAME);
                String relative = columnString(cursor, MediaStore.MediaColumns.RELATIVE_PATH);
                if ((data == null || data.isBlank()) && display != null && relative != null) {
                    File root = Environment.getExternalStorageDirectory();
                    data = new File(new File(root, relative), display).getAbsolutePath();
                }
                return new PhysicalItem(data, display, relative);
            }
        } catch (Throwable t) {
            hooks.warn("ChromeX published path query failed: " + t.getClass().getSimpleName());
        }
        return new PhysicalItem(null, null, null);
    }

    private RootDeleteResult freeRequestedSibling(PhysicalItem physical, String requested) {
        if (physical.path == null || physical.path.isBlank()) {
            return new RootDeleteResult(null, null, false, -2, "physical-path-missing");
        }
        try {
            File actual = new File(physical.path).getCanonicalFile();
            File parent = actual.getParentFile();
            if (parent == null) {
                return new RootDeleteResult(null, null, false, -3, "parent-missing");
            }
            File target = new File(parent, requested).getCanonicalFile();
            String targetPath = target.getAbsolutePath();
            if (!parent.equals(target.getParentFile())) {
                return new RootDeleteResult(targetPath, null, false, -4, "path-escape");
            }
            if (actual.equals(target)) {
                return new RootDeleteResult(targetPath, null, false, 0, "already-target");
            }

            Bundle result = runtime.application.getContentResolver().call(
                    ROOT_BRIDGE_URI,
                    RootBridgeProvider.METHOD_DELETE_DOWNLOAD,
                    requested,
                    null);
            if (result == null) {
                return new RootDeleteResult(targetPath, null, false, -8, "bridge-null");
            }
            boolean success = result.getBoolean("success", false);
            int exit = result.getInt("exit", success ? 0 : -9);
            String su = result.getString("su");
            String bridgeTarget = result.getString("target");
            String reason = result.getString("reason");
            return new RootDeleteResult(
                    bridgeTarget == null ? targetPath : bridgeTarget,
                    su,
                    success,
                    exit,
                    reason);
        } catch (Throwable t) {
            hooks.warn("ChromeX root bridge cleanup failed: " + t.getClass().getSimpleName()
                    + ":" + (t.getMessage() == null ? "" : t.getMessage()));
            return new RootDeleteResult(null, null, false, -6, t.getClass().getSimpleName());
        }
    }

    private int deletePreviousExactName(String displayName, String keepUri) {
        ContentResolver resolver = runtime.application.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        int removed = 0;
        String[] projection = {MediaStore.MediaColumns._ID};
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
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
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

    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = DownloadNamePolicy.fileNameOnly(raw);
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    private static String columnString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return null;
        String value = cursor.getString(index);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean sameUri(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try { return Uri.parse(a).normalizeScheme().equals(Uri.parse(b).normalizeScheme()); }
        catch (Throwable ignored) { return false; }
    }

    private static final class PhysicalItem {
        final String path;
        final String displayName;
        final String relativePath;

        PhysicalItem(String path, String displayName, String relativePath) {
            this.path = path;
            this.displayName = displayName;
            this.relativePath = relativePath;
        }
    }

    private static final class RootDeleteResult {
        final String target;
        final String suPath;
        final boolean deleted;
        final int exitCode;
        final String reason;

        RootDeleteResult(String target, String suPath, boolean deleted, int exitCode, String reason) {
            this.target = target;
            this.suPath = suPath;
            this.deleted = deleted;
            this.exitCode = exitCode;
            this.reason = reason;
        }
    }
}
