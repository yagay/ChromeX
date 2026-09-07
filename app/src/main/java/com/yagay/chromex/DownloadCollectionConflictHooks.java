package com.yagay.chromex;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;

/**
 * Controls Chromium's Android DownloadCollection duplicate handling.
 *
 * <p>Chromium can keep the requested logical name while MediaProvider still uniquifies the real
 * file at pending-row creation. After publish succeeds, ChromeX resolves the new item's physical
 * path, removes only the old sibling target through root, then lets Chromium's own
 * renameDownloadUri() move the new file and update MediaStore consistently.</p>
 *
 * <p>The old target is never removed before the replacement download has fully published.</p>
 */
final class DownloadCollectionConflictHooks {
    private static final String BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su"
    };

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

    /**
     * Wait for a successful publish. The newly published item may physically be "name (1).ext".
     * Free only the requested sibling path through root, then ask Chromium to rename the published
     * Uri. This preserves MediaStore bookkeeping instead of root-moving the new file behind it.
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
            return new RootDeleteResult(null, null, false, -2);
        }
        String targetPath = null;
        try {
            File actual = new File(physical.path).getCanonicalFile();
            File parent = actual.getParentFile();
            if (parent == null) return new RootDeleteResult(null, null, false, -3);
            File target = new File(parent, requested).getCanonicalFile();
            targetPath = target.getAbsolutePath();
            if (!parent.equals(target.getParentFile())) {
                return new RootDeleteResult(targetPath, null, false, -4);
            }
            if (actual.equals(target)) {
                // MediaProvider did not uniquify this download; never delete the just-published file.
                return new RootDeleteResult(targetPath, null, false, 0);
            }

            String su = resolveSuExecutable();
            if (su == null) {
                hooks.warn("ChromeX root target cleanup failed: no su executable visible to Chrome process");
                return new RootDeleteResult(targetPath, null, false, -7);
            }

            String command = "rm -f -- " + shellQuote(targetPath);
            Process process = new ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RootDeleteResult(targetPath, su, false, -5);
            }
            int exit = process.exitValue();
            boolean gone = !target.exists();
            return new RootDeleteResult(targetPath, su, exit == 0 && gone, exit);
        } catch (Throwable t) {
            hooks.warn("ChromeX root target cleanup failed: " + t.getClass().getSimpleName()
                    + ":" + (t.getMessage() == null ? "" : t.getMessage()));
            return new RootDeleteResult(targetPath, null, false, -6);
        }
    }

    private String resolveSuExecutable() {
        for (String candidate : SU_CANDIDATES) {
            try {
                File file = new File(candidate);
                if (file.isFile() && file.canExecute()) return candidate;
            } catch (Throwable ignored) {}
        }

        // Last resort: ask Android's shell resolver through an absolute /system/bin/sh path.
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", "command -v su")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                byte[] bytes = process.getInputStream().readAllBytes();
                String resolved = new String(bytes).trim();
                if (!resolved.isBlank()) return resolved;
            } else if (!finished) {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Best-effort cleanup for stale MediaStore rows after the physical replacement succeeds. */
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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

        RootDeleteResult(String target, String suPath, boolean deleted, int exitCode) {
            this.target = target;
            this.suPath = suPath;
            this.deleted = deleted;
            this.exitCode = exitCode;
        }
    }
}
