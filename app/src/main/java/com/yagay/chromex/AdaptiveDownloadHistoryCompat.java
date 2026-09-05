package com.yagay.chromex;

import android.content.SharedPreferences;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Download-history compatibility for vendor Chromium builds whose DownloadItem/DownloadInfo
 * accessors have been R8-obfuscated.
 *
 * <p>The filesystem overwrite layer publishes one exact numbered-path -> original-path mapping.
 * This class applies that mapping to the Java DownloadItem, DownloadInfo and OfflineItem objects
 * used by the browser's downloads page, and removes duplicate rows that collapse to one logical
 * path. No vendor-specific short class/field names are required.</p>
 */
final class AdaptiveDownloadHistoryCompat {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";
    private static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private Class<?> infoType;
    private Class<?> itemType;

    AdaptiveDownloadHistoryCompat(ChromeRuntime runtime, HookSupport hooks,
                                  SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            itemType = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("adaptive download history unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemCreated", "chromex:adaptive-history:created", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemUpdated", "chromex:adaptive-history:updated", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onAllDownloadsRetrieved", "chromex:adaptive-history:list", chain -> {
                    if (enabled()) rewriteAndDedupeArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "createDownloadItemList", "chromex:adaptive-history:create-list", chain -> {
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) rewriteAndDedupe((List<?>) result);
                    return result;
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "addDownloadItemToList", "chromex:adaptive-history:add-list", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (enabled()) rewriteAndDedupeArguments(chain.getArgs().toArray());
                    return result;
                });

        // Old Chromium (including Lemur 127) materializes the downloads page through
        // DownloadItem.createOfflineItem(). Rewrite that copy as well, otherwise the backing
        // DownloadInfo can be correct while the already-created UI row still shows "(n)".
        hooks.all(runtime.classLoader, DOWNLOAD_ITEM,
                "createOfflineItem", "chromex:adaptive-history:offline-item", chain -> {
                    RewriteTarget target = enabled() ? firstTarget(chain.getArgs().toArray()) : null;
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (enabled() && result != null) {
                        if (target != null) rewriteStrings(result, target);
                        else rewriteOfflineByValue(result);
                    }
                    return result;
                });

        try {
            Reflect.cls(runtime.classLoader, OFFLINE_ITEM);
            hooks.info("adaptive download history structural hooks installed");
        } catch (Throwable ignored) {
            hooks.info("adaptive download history installed without OfflineItem class check");
        }
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private void rewriteArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteListItems((List<?>) arg);
            else rewriteObject(arg);
        }
    }

    private void rewriteAndDedupeArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteAndDedupe((List<?>) arg);
            else rewriteObject(arg);
        }
    }

    private void rewriteListItems(List<?> list) {
        if (list == null) return;
        for (Object item : new ArrayList<>(list)) rewriteObject(item);
    }

    private void rewriteAndDedupe(List<?> list) {
        if (list == null || list.isEmpty()) return;
        ArrayList<Entry> entries = new ArrayList<>();
        long sequence = 0L;
        for (Object value : new ArrayList<>(list)) {
            Object item = asDownloadItem(value);
            if (item == null) continue;
            RewriteTarget target = targetFor(item);
            if (target != null) rewriteTarget(item, target);

            Object info = downloadInfoFrom(item);
            String path = infoPath(info);
            String logical = logicalPath(path);
            if (logical == null) continue;
            entries.add(new Entry(value, logical, itemTimestamp(item), ++sequence));
        }
        if (entries.isEmpty()) return;

        HashMap<String, Entry> winners = new HashMap<>();
        for (Entry entry : entries) {
            Entry current = winners.get(entry.logicalPath);
            if (current == null || entry.timestamp > current.timestamp) {
                winners.put(entry.logicalPath, entry);
            }
        }

        try {
            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object value = iterator.next();
                Entry entry = find(entries, value);
                if (entry == null) continue;
                Entry winner = winners.get(entry.logicalPath);
                if (winner != null && winner.value != value) {
                    iterator.remove();
                    hooks.info("adaptive download history deduped row: "
                            + new File(entry.logicalPath).getName());
                }
            }
        } catch (Throwable t) {
            hooks.warn("adaptive download history dedupe skipped: "
                    + t.getClass().getSimpleName());
        }
    }

    private void rewriteObject(Object value) {
        RewriteTarget target = targetFor(value);
        if (target != null) rewriteTarget(value, target);
    }

    private RewriteTarget firstTarget(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            RewriteTarget target = targetFor(arg);
            if (target != null) return target;
        }
        return null;
    }

    private RewriteTarget targetFor(Object value) {
        if (value == null) return null;
        Object info = infoType != null && infoType.isInstance(value) ? value : downloadInfoFrom(value);
        if (info == null) return null;
        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(info);
        String path = values.path;
        if (path == null || path.isBlank()) return null;

        String mapped = resolveMappedPath(path);
        if (mapped == null) return null;
        try {
            File oldFile = new File(path).getCanonicalFile();
            File target = new File(mapped).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return null;
            String oldName = values.name;
            if (oldName == null || oldName.isBlank()) oldName = oldFile.getName();
            return new RewriteTarget(oldFile.getAbsolutePath(), oldName, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveMappedPath(String path) {
        String mapped = DownloadNormalizationRegistry.resolve(path);
        if (mapped != null) return mapped;

        // Process-restart fallback: rewrite only an old numbered path that no longer exists when
        // the exact original-name file now exists in the same directory.
        try {
            File old = new File(path).getCanonicalFile();
            String original = DownloadNamePolicy.originalNameFromUniquified(old.getName());
            if (original == null || old.exists()) return null;
            File target = new File(old.getParentFile(), original).getCanonicalFile();
            return target.exists() && target.isFile() ? target.getAbsolutePath() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void rewriteTarget(Object value, RewriteTarget target) {
        if (value == null || target == null) return;
        Object info = infoType != null && infoType.isInstance(value) ? value : downloadInfoFrom(value);
        if (info != null) rewriteStrings(info, target);
        if (value != info) rewriteStrings(value, target);
    }

    private void rewriteOfflineByValue(Object offline) {
        if (offline == null) return;
        Class<?> c = offline.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(offline);
                    if (!(raw instanceof String)) continue;
                    String text = (String) raw;
                    if (!text.startsWith("/")) continue;
                    String mapped = resolveMappedPath(text);
                    if (mapped == null) continue;
                    File target = new File(mapped).getCanonicalFile();
                    RewriteTarget rewrite = new RewriteTarget(
                            new File(text).getCanonicalPath(), new File(text).getName(), target);
                    rewriteStrings(offline, rewrite);
                    return;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private void rewriteStrings(Object owner, RewriteTarget target) {
        if (owner == null || target == null) return;
        boolean changed = false;
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
                    if (target.oldPath.equals(value)) {
                        replacement = target.target.getAbsolutePath();
                    } else if (target.oldName.equals(value)) {
                        replacement = target.target.getName();
                    } else if (("file://" + target.oldPath).equals(value)) {
                        replacement = "file://" + target.target.getAbsolutePath();
                    }
                    if (replacement != null && !replacement.equals(value)) {
                        field.set(owner, replacement);
                        changed = true;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        if (changed) {
            hooks.info("adaptive download history rewritten: " + target.oldName
                    + " -> " + target.target.getName()
                    + " owner=" + owner.getClass().getName());
        }
    }

    private Object asDownloadItem(Object value) {
        return value != null && itemType != null && itemType.isInstance(value) ? value : null;
    }

    private Object downloadInfoFrom(Object value) {
        Object item = asDownloadItem(value);
        if (item == null || infoType == null) return null;
        try {
            Object stable = Reflect.call(item, "getDownloadInfo");
            if (stable != null && infoType.isInstance(stable)) return stable;
        } catch (Throwable ignored) {}
        return Reflect.findFieldValueByType(item, infoType);
    }

    private String infoPath(Object info) {
        if (info == null) return null;
        try {
            Object stable = Reflect.call(info, "getFilePath");
            if (stable instanceof String && !((String) stable).isBlank()) return (String) stable;
        } catch (Throwable ignored) {}
        AdaptiveDownloadInfo.Values values = AdaptiveDownloadInfo.extract(info);
        return values.path;
    }

    private String logicalPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        String mapped = DownloadNormalizationRegistry.logicalPath(path);
        if (mapped != null && !mapped.equals(path)) return mapped;
        String fallback = resolveMappedPath(path);
        if (fallback != null) return fallback;
        try { return new File(path).getCanonicalPath(); }
        catch (Throwable ignored) { return null; }
    }

    private long itemTimestamp(Object item) {
        long best = 0L;
        Class<?> c = item == null ? null : item.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(item);
                    // Epoch-millisecond-like values are the safest cross-version signal for the
                    // newer history row. Ignore native pointers, counters and zero bookkeeping.
                    if (value >= 946684800000L && value <= 4102444800000L && value > best) best = value;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return best;
    }

    private static Entry find(List<Entry> entries, Object value) {
        for (Entry entry : entries) if (entry.value == value) return entry;
        return null;
    }

    private static final class RewriteTarget {
        final String oldPath;
        final String oldName;
        final File target;

        RewriteTarget(String oldPath, String oldName, File target) {
            this.oldPath = oldPath;
            this.oldName = oldName;
            this.target = target;
        }
    }

    private static final class Entry {
        final Object value;
        final String logicalPath;
        final long timestamp;
        final long sequence;

        Entry(Object value, String logicalPath, long timestamp, long sequence) {
            this.value = value;
            this.logicalPath = logicalPath;
            this.timestamp = timestamp;
            this.sequence = sequence;
        }
    }
}
