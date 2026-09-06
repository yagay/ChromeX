package com.yagay.chromex;

import android.content.SharedPreferences;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Final display-name compatibility for adaptive/vendor Chromium downloads pages.
 *
 * <p>Vendor Chromium can materialize the downloads-page OfflineItem before ChromeX has finished
 * moving the uniquified file back to its original name. This layer records the exact duplicate
 * task while the duplicate bridge is being accepted, then rewrites only the UI copy returned by
 * DownloadItem.createOfflineItem(). It never changes the real DownloadInfo before completion.</p>
 */
final class AdaptiveOfflineItemDisplayHooks {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final long ACTIVE_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_ACTIVE = 64;
    private static final Object ACTIVE_LOCK = new Object();
    private static final ArrayList<ActiveTarget> ACTIVE = new ArrayList<>();

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

        // Installed before the overwrite hook. This outer hook records the exact original name,
        // then continues the hook chain so AdaptiveSameNameOverwriteHooks can accept the dialog.
        hooks.all(runtime.classLoader, DUPLICATE_BRIDGE,
                "showDialog", "chromex:adaptive-offline:capture", chain -> {
                    if (enabled()) rememberDuplicate(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, DOWNLOAD_ITEM,
                "createOfflineItem", "chromex:adaptive-offline:display", chain -> {
                    Object item = findItem(chain.getThisObject(), chain.getArgs().toArray());
                    DisplayTarget target = enabled() ? displayTarget(item) : null;
                    Object result = chain.proceed();
                    if (enabled() && result != null) {
                        // The pre-call DownloadInfo can still be incomplete in old Chromium.
                        // Always give the freshly-created OfflineItem itself one final structural
                        // pass, which is the authoritative object consumed by the downloads page.
                        if (target == null) target = displayTargetFromOffline(result);
                        if (target != null && rewriteStrings(result, target)) {
                            hooks.info("adaptive offline display normalized: "
                                    + target.numberedName + " -> " + target.newFile.getName()
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

    private void rememberDuplicate(Object[] args) {
        String rawPath = firstSharedPath(args);
        String baseName = DownloadNamePolicy.fileNameOnly(rawPath);
        if (baseName == null || baseName.isBlank()) return;
        long callback = lastLong(args);
        long now = System.currentTimeMillis();
        synchronized (ACTIVE_LOCK) {
            pruneActiveLocked(now);
            if (callback != 0L) ACTIVE.removeIf(old -> old.callback == callback);
            ACTIVE.add(new ActiveTarget(callback, baseName, now));
            while (ACTIVE.size() > MAX_ACTIVE) ACTIVE.remove(0);
        }
        hooks.info("adaptive offline display armed: name=" + baseName + " callback=" + callback);
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
        return targetForPath(rawPath, values.name, null);
    }

    private DisplayTarget displayTargetFromOffline(Object offline) {
        if (offline == null) return null;
        ArrayList<String> strings = stringValues(offline);
        for (String value : strings) {
            if (value == null || !value.startsWith("/")) continue;
            DisplayTarget target = targetForPath(value, null, strings);
            if (target != null) return target;
        }
        return null;
    }

    private DisplayTarget targetForPath(String rawPath, String reportedName,
                                        List<String> siblingStrings) {
        try {
            File oldFile = new File(rawPath).getCanonicalFile();
            String numberedName = oldFile.getName();
            String oldName = reportedName;
            if (oldName == null || oldName.isBlank()) oldName = numberedName;

            String mapped = DownloadNormalizationRegistry.resolve(oldFile.getAbsolutePath());
            if (mapped != null) {
                File mappedFile = new File(mapped).getCanonicalFile();
                if (mappedFile.exists() && mappedFile.isFile()) {
                    return new DisplayTarget(oldFile.getAbsolutePath(), oldName, numberedName,
                            mappedFile, "exact-mapping");
                }
            }

            String activeBase = activeOriginalName(numberedName, oldName, siblingStrings);
            if (activeBase != null && !activeBase.equals(numberedName)) {
                // UI-only prediction: the physical numbered file may not exist yet, and the old
                // original file may still be present. The active duplicate task is the exact proof
                // that ChromeX will normalize this numbered path to activeBase on completion.
                File desired = new File(oldFile.getParentFile(), activeBase).getCanonicalFile();
                return new DisplayTarget(oldFile.getAbsolutePath(), oldName, numberedName,
                        desired, "active-pending");
            }

            // Safe process-restart/history fallback. A numbered history path that no longer exists
            // while its exact original-name sibling does exist is a completed normalization.
            String original = DownloadNamePolicy.originalNameFromUniquified(numberedName);
            if (original != null && !oldFile.exists()) {
                File originalFile = new File(oldFile.getParentFile(), original).getCanonicalFile();
                if (originalFile.exists() && originalFile.isFile()) {
                    return new DisplayTarget(oldFile.getAbsolutePath(), oldName, numberedName,
                            originalFile, "stale-normalized");
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String activeOriginalName(String numberedName, String reportedName,
                                             List<String> siblingStrings) {
        long now = System.currentTimeMillis();
        synchronized (ACTIVE_LOCK) {
            pruneActiveLocked(now);
            for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                String base = ACTIVE.get(i).baseName;
                if (matchesActive(base, numberedName) || matchesActive(base, reportedName)) {
                    return base;
                }
                if (siblingStrings != null) {
                    for (String value : siblingStrings) {
                        String name = DownloadNamePolicy.fileNameOnly(value);
                        if (matchesActive(base, name)) return base;
                    }
                }
            }
        }
        return null;
    }

    private static boolean matchesActive(String base, String value) {
        if (base == null || value == null || value.isBlank()) return false;
        return base.equals(value) || DownloadNamePolicy.matchesUniquifiedName(base, value);
    }

    private static void pruneActiveLocked(long now) {
        Iterator<ActiveTarget> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().time > ACTIVE_TTL_MS) iterator.remove();
        }
    }

    private static ArrayList<String> stringValues(Object owner) {
        ArrayList<String> out = new ArrayList<>();
        Class<?> c = owner == null ? null : owner.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (raw instanceof String) out.add((String) raw);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private boolean rewriteStrings(Object owner, DisplayTarget target) {
        if (owner == null || target == null) return false;
        boolean changed = false;
        String oldPath = target.oldPath;
        String oldName = target.oldName;
        String numberedName = target.numberedName;
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
                    else if (oldName.equals(value) || numberedName.equals(value)) replacement = newName;
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

    private static String firstSharedPath(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).startsWith("/")) return (String) arg;
        }
        return null;
    }

    private static long lastLong(Object[] args) {
        if (args == null) return 0L;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
        }
        return 0L;
    }

    private static final class ActiveTarget {
        final long callback;
        final String baseName;
        final long time;

        ActiveTarget(long callback, String baseName, long time) {
            this.callback = callback;
            this.baseName = baseName;
            this.time = time;
        }
    }

    private static final class DisplayTarget {
        final String oldPath;
        final String oldName;
        final String numberedName;
        final File newFile;
        final String source;

        DisplayTarget(String oldPath, String oldName, String numberedName,
                      File newFile, String source) {
            this.oldPath = oldPath;
            this.oldName = oldName;
            this.numberedName = numberedName;
            this.newFile = newFile;
            this.source = source;
        }
    }
}
