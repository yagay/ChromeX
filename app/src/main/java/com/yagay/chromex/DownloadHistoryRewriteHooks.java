package com.yagay.chromex;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Keeps Chrome's downloads UI aligned with same-name filesystem replacement. */
final class DownloadHistoryRewriteHooks {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final Map<String, HistoryEntry> newestByTarget = new HashMap<>();
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);

    DownloadHistoryRewriteHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("download history rewrite unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemCreated", "chromex:history:download-created", chain -> {
                    if (!enabled() || Boolean.TRUE.equals(replaying.get())) return chain.proceed();
                    LiveResult live = rewriteLiveArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    removeOlderLive(chain.getThisObject(), live);
                    return result;
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemUpdated", "chromex:history:download-updated", chain -> {
                    if (!enabled() || Boolean.TRUE.equals(replaying.get())) return chain.proceed();
                    LiveResult live = rewriteLiveArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    removeOlderLive(chain.getThisObject(), live);
                    return result;
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onAllDownloadsRetrieved", "chromex:history:download-list", chain -> {
                    if (enabled()) rewriteFullLists(chain.getArgs().toArray());
                    return chain.proceed();
                });
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private LiveResult rewriteLiveArguments(Object[] args) {
        if (args == null) return null;
        LiveResult best = null;
        for (Object arg : args) {
            if (arg instanceof List<?>) continue;
            Object item = asDownloadItem(arg);
            if (item == null) continue;
            RewriteResult rewrite = rewriteObject(item, true);
            HistoryEntry current = entryFor(item, rewrite);
            if (current == null) continue;
            HistoryEntry older = rememberNewest(current);
            if (older != null && !sameId(older.id, current.id)) {
                best = new LiveResult(current, older);
            }
        }
        return best;
    }

    private void removeOlderLive(Object service, LiveResult live) {
        if (service == null || live == null || live.older == null || live.older.id == null) return;
        try {
            replaying.set(true);
            Reflect.call(service, "onDownloadItemRemoved", live.older.id);
            hooks.info("download history removed older live item: " + live.older.id
                    + " target=" + new File(live.current.targetPath).getName());
        } catch (Throwable t) {
            hooks.warn("download history live dedupe skipped: " + t.getClass().getSimpleName());
        } finally {
            replaying.set(false);
        }
    }

    private void rewriteFullLists(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteList((List<?>) arg);
        }
    }

    private void rewriteList(List<?> list) {
        if (list == null || list.isEmpty()) return;
        ArrayList<HistoryEntry> entries = new ArrayList<>();
        for (Object item : new ArrayList<>(list)) {
            Object downloadItem = asDownloadItem(item);
            if (downloadItem == null) continue;
            RewriteResult rewrite = rewriteObject(downloadItem, true);
            HistoryEntry entry = entryFor(downloadItem, rewrite);
            if (entry != null) entries.add(entry);
        }
        if (entries.isEmpty()) return;

        HashMap<String, HistoryEntry> winners = new HashMap<>();
        for (HistoryEntry entry : entries) {
            HistoryEntry previous = winners.get(entry.targetPath);
            if (previous == null || newer(entry, previous)) winners.put(entry.targetPath, entry);
        }

        try {
            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                HistoryEntry entry = findEntry(entries, item);
                if (entry == null) continue;
                HistoryEntry winner = winners.get(entry.targetPath);
                if (winner != null && winner.item != item) {
                    iterator.remove();
                    hooks.info("download history deduped older item: " + safeId(entry.id)
                            + " -> kept=" + safeId(winner.id)
                            + " target=" + new File(entry.targetPath).getName());
                }
            }
        } catch (Throwable t) {
            hooks.warn("download history list dedupe skipped: " + t.getClass().getSimpleName());
        }

        synchronized (lock) {
            newestByTarget.clear();
            newestByTarget.putAll(winners);
        }
    }

    private HistoryEntry rememberNewest(HistoryEntry current) {
        synchronized (lock) {
            HistoryEntry previous = newestByTarget.get(current.targetPath);
            if (previous == null || newer(current, previous)) {
                newestByTarget.put(current.targetPath, current);
                return previous;
            }
            return null;
        }
    }

    private static boolean newer(HistoryEntry a, HistoryEntry b) {
        if (a.startTime != b.startTime) return a.startTime > b.startTime;
        return a.sequence > b.sequence;
    }

    private static long sequenceCounter;

    private HistoryEntry entryFor(Object item, RewriteResult rewrite) {
        Object info = downloadInfoFrom(item);
        String path = rewrite != null ? rewrite.targetPath : canonicalPath(infoPath(info));
        if (path == null) return null;
        String id = itemId(item);
        long start = itemStartTime(item);
        synchronized (DownloadHistoryRewriteHooks.class) {
            return new HistoryEntry(item, id, path, start, ++sequenceCounter);
        }
    }

    private static HistoryEntry findEntry(List<HistoryEntry> entries, Object item) {
        for (HistoryEntry entry : entries) if (entry.item == item) return entry;
        return null;
    }

    private RewriteResult rewriteObject(Object value, boolean allowLivePrediction) {
        if (value == null) return null;
        try {
            Class<?> infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            if (infoType.isAssignableFrom(value.getClass())) return rewriteInfo(value, allowLivePrediction);
        } catch (Throwable ignored) {}
        return rewriteInfo(downloadInfoFrom(value), allowLivePrediction);
    }

    private Object asDownloadItem(Object value) {
        if (value == null) return null;
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
            return type.isAssignableFrom(value.getClass()) ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object downloadInfoFrom(Object item) {
        Object downloadItem = asDownloadItem(item);
        if (downloadItem == null) return null;
        try { return Reflect.call(downloadItem, "getDownloadInfo"); }
        catch (Throwable ignored) { return null; }
    }

    private RewriteResult rewriteInfo(Object info, boolean allowLivePrediction) {
        if (info == null) return null;
        String path = infoPath(info);
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;

        File oldPath;
        try { oldPath = new File(path).getCanonicalFile(); }
        catch (Throwable ignored) { return null; }

        String oldName = oldPath.getName();
        String originalName = DownloadNamePolicy.originalNameFromUniquified(oldName);
        if (originalName == null) {
            originalName = DownloadNamePolicy.originalNameFromUniquified(infoName(info));
        }
        if (originalName == null) return null;

        File parent = oldPath.getParentFile();
        if (parent == null) return null;
        File target;
        try { target = new File(parent, originalName).getCanonicalFile(); }
        catch (Throwable ignored) { return null; }

        // For a live create/update event the old uniquified file may still exist because the
        // filesystem normalizer runs immediately after Chrome's completion callback. When overwrite
        // mode is enabled and the original-name target already exists in the same directory, this
        // is exactly the conflict ChromeX is about to replace; rewrite before observers see it.
        if (!target.exists() || !target.isFile()) return null;
        if (!allowLivePrediction && oldPath.exists()) return null;

        String targetPath = target.getAbsolutePath();
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, targetPath); } catch (Throwable ignored) {}
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, target.getName()); } catch (Throwable ignored) {}
        try { Reflect.set(info, "f", target.getName()); } catch (Throwable ignored) {}
        hooks.info("download history rewritten: " + oldName + " -> " + target.getName());
        return new RewriteResult(targetPath);
    }

    private String infoPath(Object info) {
        if (info == null) return null;
        try {
            Object value = Reflect.call(info, "getFilePath");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        if (Chrome152.matches(runtime)) {
            try {
                Object value = Reflect.get(info, Chrome152.DOWNLOAD_INFO_PATH);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String infoName(Object info) {
        if (info == null) return null;
        try {
            Object value = Reflect.call(info, "getFileName");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        if (Chrome152.matches(runtime)) {
            try {
                Object value = Reflect.get(info, Chrome152.DOWNLOAD_INFO_NAME);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String itemId(Object item) {
        try {
            Object value = Reflect.call(item, "getId");
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private long itemStartTime(Object item) {
        try {
            Object value = Reflect.call(item, "getStartTime");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        try { return new File(path).getCanonicalPath(); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean sameId(String a, String b) {
        return a != null && b != null && a.equals(b);
    }

    private static String safeId(String id) {
        return id == null || id.isBlank() ? "<unknown>" : id;
    }

    private static final class RewriteResult {
        final String targetPath;
        RewriteResult(String targetPath) { this.targetPath = targetPath; }
    }

    private static final class HistoryEntry {
        final Object item;
        final String id;
        final String targetPath;
        final long startTime;
        final long sequence;

        HistoryEntry(Object item, String id, String targetPath, long startTime, long sequence) {
            this.item = item;
            this.id = id;
            this.targetPath = targetPath;
            this.startTime = startTime;
            this.sequence = sequence;
        }
    }

    private static final class LiveResult {
        final HistoryEntry current;
        final HistoryEntry older;
        LiveResult(HistoryEntry current, HistoryEntry older) {
            this.current = current;
            this.older = older;
        }
    }
}
