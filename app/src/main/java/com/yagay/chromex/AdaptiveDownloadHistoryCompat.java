package com.yagay.chromex;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.ref.WeakReference;
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
 * used by the browser's downloads page, removes duplicate rows that collapse to one logical path,
 * and actively re-dispatches the updated DownloadItem after a completed normalization so an
 * already-visible downloads page does not keep a stale "(n)" OfflineItem.</p>
 */
final class AdaptiveDownloadHistoryCompat {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";
    private static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final int MAX_RECENT_ITEMS = 192;
    private static final long[] PROPAGATE_DELAYS_MS = {0L, 250L, 900L, 2200L};

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object recentLock = new Object();
    private final ArrayList<WeakReference<Object>> recentItems = new ArrayList<>();
    private Class<?> infoType;
    private Class<?> itemType;
    private Class<?> serviceType;
    private volatile Object serviceInstance;

    AdaptiveDownloadHistoryCompat(ChromeRuntime runtime, HookSupport hooks,
                                  SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            serviceType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            itemType = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("adaptive download history unavailable: " + t.getClass().getSimpleName());
            return;
        }

        // Adaptive browsers use this listener instead of the official-Chrome getAllDownloads()
        // refresh path. The exact old/new mapping arrives only after the filesystem transaction
        // succeeds, which is the correct moment to mutate and re-dispatch already-created rows.
        DownloadNormalizationRegistry.setListener((oldPath, newPath) -> {
            if (!enabled()) return;
            for (long delay : PROPAGATE_DELAYS_MS) {
                main.postDelayed(() -> propagateNormalization(oldPath, newPath, delay), delay);
            }
        });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemCreated", "chromex:adaptive-history:created", chain -> {
                    captureService(chain.getThisObject());
                    if (enabled()) {
                        rememberArguments(chain.getArgs().toArray());
                        rewriteArguments(chain.getArgs().toArray());
                    }
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemUpdated", "chromex:adaptive-history:updated", chain -> {
                    captureService(chain.getThisObject());
                    if (enabled()) {
                        rememberArguments(chain.getArgs().toArray());
                        rewriteArguments(chain.getArgs().toArray());
                    }
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onAllDownloadsRetrieved", "chromex:adaptive-history:list", chain -> {
                    captureService(chain.getThisObject());
                    if (enabled()) {
                        rememberArguments(chain.getArgs().toArray());
                        rewriteAndDedupeArguments(chain.getArgs().toArray());
                    }
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "createDownloadItemList", "chromex:adaptive-history:create-list", chain -> {
                    captureService(chain.getThisObject());
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) {
                        rememberList((List<?>) result);
                        rewriteAndDedupe((List<?>) result);
                    }
                    return result;
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "addDownloadItemToList", "chromex:adaptive-history:add-list", chain -> {
                    captureService(chain.getThisObject());
                    if (enabled()) {
                        rememberArguments(chain.getArgs().toArray());
                        rewriteArguments(chain.getArgs().toArray());
                    }
                    Object result = chain.proceed();
                    if (enabled()) rewriteAndDedupeArguments(chain.getArgs().toArray());
                    return result;
                });

        // Old Chromium (including Lemur 127) materializes the downloads page through
        // DownloadItem.createOfflineItem(). The DownloadItem itself is commonly the receiver and
        // there may be no useful method arguments, so always derive the target from the receiver
        // first. This was the missing path in the first adaptive-history implementation.
        hooks.all(runtime.classLoader, DOWNLOAD_ITEM,
                "createOfflineItem", "chromex:adaptive-history:offline-item", chain -> {
                    Object receiver = chain.getThisObject();
                    if (enabled()) rememberItem(receiver);
                    RewriteTarget target = enabled() ? targetFor(receiver) : null;
                    if (target == null && enabled()) target = firstTarget(chain.getArgs().toArray());
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

    private void captureService(Object value) {
        if (value != null && serviceType != null && serviceType.isInstance(value)) {
            serviceInstance = value;
        }
    }

    private Object currentService() {
        Object current = serviceInstance;
        if (current != null) return current;
        try {
            current = AdaptiveDexResolver.singletonOwner(serviceType);
            if (current != null) serviceInstance = current;
        } catch (Throwable ignored) {}
        return current;
    }

    private void rememberArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rememberList((List<?>) arg);
            else rememberItem(arg);
        }
    }

    private void rememberList(List<?> list) {
        if (list == null) return;
        for (Object value : new ArrayList<>(list)) rememberItem(value);
    }

    private void rememberItem(Object value) {
        Object item = asDownloadItem(value);
        if (item == null) return;
        synchronized (recentLock) {
            boolean found = false;
            Iterator<WeakReference<Object>> iterator = recentItems.iterator();
            while (iterator.hasNext()) {
                Object existing = iterator.next().get();
                if (existing == null) {
                    iterator.remove();
                } else if (existing == item) {
                    found = true;
                }
            }
            if (!found) recentItems.add(new WeakReference<>(item));
            while (recentItems.size() > MAX_RECENT_ITEMS) recentItems.remove(0);
        }
    }

    private List<Object> recentSnapshot() {
        ArrayList<Object> out = new ArrayList<>();
        synchronized (recentLock) {
            Iterator<WeakReference<Object>> iterator = recentItems.iterator();
            while (iterator.hasNext()) {
                Object value = iterator.next().get();
                if (value == null) iterator.remove();
                else out.add(value);
            }
        }
        return out;
    }

    private void propagateNormalization(String oldPath, String newPath, long delay) {
        if (!enabled()) return;
        RewriteTarget target = exactTarget(oldPath, newPath);
        if (target == null) return;

        int rewritten = 0;
        int redispatched = 0;
        for (Object item : recentSnapshot()) {
            Object info = downloadInfoFrom(item);
            String path = infoPath(info);
            if (!samePath(path, target.oldPath) && !containsOldValue(item, target)) continue;
            rewriteTarget(item, target);
            rewritten++;
            if (redispatchUpdatedItem(item)) redispatched++;
        }

        if (rewritten > 0 || delay == 0L) {
            hooks.info("adaptive download history propagated normalization: "
                    + target.oldName + " -> " + target.target.getName()
                    + " items=" + rewritten + " redispatched=" + redispatched
                    + " delay=" + delay);
        }
    }

    private RewriteTarget exactTarget(String oldPath, String newPath) {
        if (oldPath == null || newPath == null) return null;
        try {
            File oldFile = new File(oldPath).getCanonicalFile();
            File target = new File(newPath).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return null;
            return new RewriteTarget(oldFile.getAbsolutePath(), oldFile.getName(), target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean redispatchUpdatedItem(Object item) {
        Object service = currentService();
        if (service == null || item == null) return false;
        try {
            Reflect.call(service, "onDownloadItemUpdated", item);
            return true;
        } catch (Throwable t) {
            if (t.getCause() instanceof StackOverflowError) return false;
            return false;
        }
    }

    private boolean containsOldValue(Object owner, RewriteTarget target) {
        if (owner == null || target == null) return false;
        Object info = infoType != null && infoType.isInstance(owner) ? owner : downloadInfoFrom(owner);
        if (info != null && containsOldString(info, target)) return true;
        return owner != info && containsOldString(owner, target);
    }

    private boolean containsOldString(Object owner, RewriteTarget target) {
        Class<?> c = owner == null ? null : owner.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    if (target.oldPath.equals(value) || target.oldName.equals(value)
                            || ("file://" + target.oldPath).equals(value)) return true;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean samePath(String first, String second) {
        if (first == null || second == null) return false;
        try { return new File(first).getCanonicalFile().equals(new File(second).getCanonicalFile()); }
        catch (Throwable ignored) { return first.equals(second); }
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
            rememberItem(item);
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
            if (current == null || entry.timestamp > current.timestamp
                    || (entry.timestamp == current.timestamp && entry.sequence > current.sequence)) {
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
        rememberItem(value);
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
