package com.yagay.chromex;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Keeps Chrome's Java downloads view consistent with completed overwrite transactions. */
final class DownloadHistoryRewriteHooks {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

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

        DownloadNormalizationRegistry.setListener((oldPath, newPath) ->
                main.post(() -> refreshNativeHistory(oldPath, newPath)));

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemCreated", "chromex:history:download-created", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemUpdated", "chromex:history:download-updated", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onAllDownloadsRetrieved", "chromex:history:download-list", chain -> {
                    if (enabled()) rewriteAndDedupeLists(chain.getArgs().toArray());
                    return chain.proceed();
                });
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private void refreshNativeHistory(String oldPath, String newPath) {
        if (!enabled()) return;
        try {
            Class<?> serviceClass = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            Object service = null;
            try { service = Reflect.callStatic(serviceClass, "getDownloadManagerService"); }
            catch (Throwable ignored) {}
            if (service == null) {
                hooks.warn("download history refresh skipped: service unavailable");
                return;
            }

            Method chosen = null;
            for (Method method : serviceClass.getMethods()) {
                if (!"getAllDownloads".equals(method.getName()) || method.getParameterCount() != 1) continue;
                chosen = method;
                break;
            }
            if (chosen == null) {
                for (Method method : serviceClass.getDeclaredMethods()) {
                    if (!"getAllDownloads".equals(method.getName()) || method.getParameterCount() != 1) continue;
                    method.setAccessible(true);
                    chosen = method;
                    break;
                }
            }
            if (chosen == null) {
                hooks.warn("download history refresh skipped: getAllDownloads unavailable");
                return;
            }

            Class<?> parameter = chosen.getParameterTypes()[0];
            Object argument = parameter == boolean.class || parameter == Boolean.class
                    ? Boolean.FALSE : null;
            chosen.invoke(service, argument);
            hooks.info("download history refresh requested after overwrite: "
                    + new File(oldPath).getName() + " -> " + new File(newPath).getName());
        } catch (Throwable t) {
            hooks.warn("download history refresh failed: " + t.getClass().getSimpleName());
        }
    }

    private void rewriteArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteListItems((List<?>) arg);
            else rewriteObject(arg);
        }
    }

    private void rewriteAndDedupeLists(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteAndDedupe((List<?>) arg);
        }
    }

    private void rewriteListItems(List<?> list) {
        if (list == null) return;
        for (Object item : new ArrayList<>(list)) rewriteObject(item);
    }

    private void rewriteAndDedupe(List<?> list) {
        if (list == null || list.isEmpty()) return;
        ArrayList<Entry> entries = new ArrayList<>();
        for (Object item : new ArrayList<>(list)) {
            Object downloadItem = asDownloadItem(item);
            if (downloadItem == null) continue;
            rewriteObject(downloadItem);
            Entry entry = entryFor(downloadItem);
            if (entry != null) entries.add(entry);
        }
        if (entries.isEmpty()) return;

        HashMap<String, Entry> winners = new HashMap<>();
        for (Entry entry : entries) {
            Entry old = winners.get(entry.logicalPath);
            if (old == null || entry.startTime > old.startTime
                    || (entry.startTime == old.startTime && entry.sequence > old.sequence)) {
                winners.put(entry.logicalPath, entry);
            }
        }

        try {
            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                Entry entry = find(entries, item);
                if (entry == null) continue;
                Entry winner = winners.get(entry.logicalPath);
                if (winner != null && winner.item != item) {
                    iterator.remove();
                    hooks.info("download history deduped older item: " + safeId(entry.id)
                            + " -> kept=" + safeId(winner.id)
                            + " target=" + new File(entry.logicalPath).getName());
                }
            }
        } catch (Throwable t) {
            hooks.warn("download history list dedupe skipped: " + t.getClass().getSimpleName());
        }
    }

    private void rewriteObject(Object value) {
        if (value == null) return;
        Object info;
        try {
            Class<?> infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            info = infoType.isAssignableFrom(value.getClass()) ? value : downloadInfoFrom(value);
        } catch (Throwable ignored) {
            info = null;
        }
        rewriteInfo(info);
    }

    private void rewriteInfo(Object info) {
        if (info == null) return;
        String path = infoPath(info);
        if (path == null) return;

        String mapped = DownloadNormalizationRegistry.resolve(path);
        if (mapped == null) {
            // Process-restart fallback: only rewrite a stale numbered path when it no longer exists
            // and the exact original-name file exists in the same directory.
            try {
                File old = new File(path).getCanonicalFile();
                String original = DownloadNamePolicy.originalNameFromUniquified(old.getName());
                if (original != null && !old.exists()) {
                    File candidate = new File(old.getParentFile(), original).getCanonicalFile();
                    if (candidate.exists() && candidate.isFile()) mapped = candidate.getAbsolutePath();
                }
            } catch (Throwable ignored) {}
        }
        if (mapped == null) return;

        try {
            File target = new File(mapped).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return;
            try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, target.getAbsolutePath()); }
            catch (Throwable ignored) {}
            try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, target.getName()); }
            catch (Throwable ignored) {}
            try { Reflect.set(info, "f", target.getName()); } catch (Throwable ignored) {}
            hooks.info("download history rewritten from exact normalization: "
                    + new File(path).getName() + " -> " + target.getName());
        } catch (Throwable ignored) {}
    }

    private Entry entryFor(Object item) {
        Object info = downloadInfoFrom(item);
        String path = infoPath(info);
        String logical = DownloadNormalizationRegistry.logicalPath(path);
        if (logical == null) logical = canonicalPath(path);
        if (logical == null) return null;
        return new Entry(item, itemId(item), logical, itemStartTime(item), nextSequence());
    }

    private Object asDownloadItem(Object value) {
        if (value == null) return null;
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
            return type.isAssignableFrom(value.getClass()) ? value : null;
        } catch (Throwable ignored) { return null; }
    }

    private Object downloadInfoFrom(Object item) {
        Object downloadItem = asDownloadItem(item);
        if (downloadItem == null) return null;
        try { return Reflect.call(downloadItem, "getDownloadInfo"); }
        catch (Throwable ignored) { return null; }
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

    private String itemId(Object item) {
        try {
            Object value = Reflect.call(item, "getId");
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) { return null; }
    }

    private long itemStartTime(Object item) {
        try {
            Object value = Reflect.call(item, "getStartTime");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) { return 0L; }
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        try { return new File(path).getCanonicalPath(); }
        catch (Throwable ignored) { return null; }
    }

    private static long sequence;
    private static synchronized long nextSequence() { return ++sequence; }

    private static Entry find(List<Entry> entries, Object item) {
        for (Entry entry : entries) if (entry.item == item) return entry;
        return null;
    }

    private static String safeId(String id) {
        return id == null || id.isBlank() ? "<unknown>" : id;
    }

    private static final class Entry {
        final Object item;
        final String id;
        final String logicalPath;
        final long startTime;
        final long sequence;

        Entry(Object item, String id, String logicalPath, long startTime, long sequence) {
            this.item = item;
            this.id = id;
            this.logicalPath = logicalPath;
            this.startTime = startTime;
            this.sequence = sequence;
        }
    }
}
