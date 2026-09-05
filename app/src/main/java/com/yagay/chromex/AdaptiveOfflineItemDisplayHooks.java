package com.yagay.chromex;

import android.content.SharedPreferences;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Final display-name compatibility for adaptive/vendor Chromium downloads pages.
 *
 * <p>Some old Chromium forks materialize an OfflineItem before ChromeX has finished moving the
 * uniquified file back to its original name. Rewriting DownloadInfo later cannot change that
 * already-created UI copy. This hook therefore normalizes only the OfflineItem returned by
 * DownloadItem.createOfflineItem(). It never mutates DownloadItem/DownloadInfo before completion,
 * so the filesystem overwrite transaction can still locate the real "name (n).ext" file.</p>
 */
final class AdaptiveOfflineItemDisplayHooks {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";
    private static final long RECENT_WINDOW_MS = 15L * 60L * 1000L;

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private Class<?> infoType;
    private Class<?> itemType;

    AdaptiveOfflineItemDisplayHooks(ChromeRuntime runtime, HookSupport hooks,
                                    SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            itemType = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("adaptive offline display unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, DOWNLOAD_ITEM,
                "createOfflineItem", "chromex:adaptive-offline:display", chain -> {
                    Object item = findItem(chain.getThisObject(), chain.getArgs().toArray());
                    DisplayTarget target = enabled() ? displayTarget(item) : null;
                    Object result = chain.proceed();
                    if (enabled() && result != null && target != null) {
                        if (rewriteStrings(result, target)) {
                            hooks.info("adaptive offline display normalized: "
                                    + target.oldName + " -> " + target.newFile.getName()
                                    + " source=" + target.source);
                        }
                    }
                    return result;
                });
        hooks.info("adaptive offline final display hook installed");
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private Object findItem(Object receiver, Object[] args) {
        if (itemType != null && receiver != null && itemType.isInstance(receiver)) return receiver;
        if (args != null && itemType != null) {
            for (Object arg : args) if (arg != null && itemType.isInstance(arg)) return arg;
        }
        return null;
    }

    private DisplayTarget displayTarget(Object item) {
        if (item == null || infoType == null) return null;
        Object info = Reflect.findFieldValueByType(item, infoType);
        if (info == null && infoType.isInstance(item)) info = item;
        if (info == null) return null;

        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(info);
        String rawPath = values.path;
        if (rawPath == null || rawPath.isBlank() || !rawPath.startsWith("/")) return null;

        try {
            File oldFile = new File(rawPath).getCanonicalFile();
            String oldName = values.name;
            if (oldName == null || oldName.isBlank()) oldName = oldFile.getName();

            String mapped = DownloadNormalizationRegistry.resolve(oldFile.getAbsolutePath());
            if (mapped != null) {
                File mappedFile = new File(mapped).getCanonicalFile();
                if (mappedFile.exists() && mappedFile.isFile()) {
                    return new DisplayTarget(oldFile.getAbsolutePath(), oldName,
                            mappedFile, "exact-mapping");
                }
            }

            String original = DownloadNamePolicy.originalNameFromUniquified(oldFile.getName());
            if (original == null || original.isBlank()) return null;
            File originalFile = new File(oldFile.getParentFile(), original).getCanonicalFile();
            if (!originalFile.exists() || !originalFile.isFile()) return null;

            // This prediction is UI-only and intentionally conservative. It is allowed only for a
            // freshly produced numbered item while the original sibling exists, which is exactly
            // the duplicate-overwrite situation. Old unrelated history rows are left untouched.
            if (!isRecent(oldFile, item)) return null;
            return new DisplayTarget(oldFile.getAbsolutePath(), oldName,
                    originalFile, "active-conflict");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isRecent(File oldFile, Object item) {
        long now = System.currentTimeMillis();
        try {
            if (oldFile.exists()) {
                long modified = oldFile.lastModified();
                if (modified > 0L && Math.abs(now - modified) <= RECENT_WINDOW_MS) return true;
            }
        } catch (Throwable ignored) {}

        Class<?> c = item == null ? null : item.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(item);
                    if (value >= 946684800000L && value <= 4102444800000L
                            && Math.abs(now - value) <= RECENT_WINDOW_MS) return true;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private boolean rewriteStrings(Object owner, DisplayTarget target) {
        if (owner == null || target == null) return false;
        boolean changed = false;
        String oldPath = target.oldPath;
        String oldName = target.oldName;
        String newPath = target.newFile.getAbsolutePath();
        String newName = target.newFile.getName();

        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    String replacement = null;
                    if (oldPath.equals(value)) replacement = newPath;
                    else if (oldName.equals(value)) replacement = newName;
                    else if (("file://" + oldPath).equals(value)) replacement = "file://" + newPath;
                    if (replacement != null && !replacement.equals(value)) {
                        field.set(owner, replacement);
                        changed = true;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return changed;
    }

    private static final class DisplayTarget {
        final String oldPath;
        final String oldName;
        final File newFile;
        final String source;

        DisplayTarget(String oldPath, String oldName, File newFile, String source) {
            this.oldPath = oldPath;
            this.oldName = oldName;
            this.newFile = newFile;
            this.source = source;
        }
    }
}
