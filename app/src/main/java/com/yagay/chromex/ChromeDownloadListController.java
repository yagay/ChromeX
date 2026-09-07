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
 * Single owner for the visible Chromium download list.
 *
 * <p>The controller does not replace Chrome's UI. It intercepts the list boundaries that feed the
 * UI, rewrites entries through {@link DownloadNormalizationRegistry}, and collapses entries that
 * represent the same logical file. File replacement remains the responsibility of the overwrite
 * hooks; this class owns only presentation/list state.</p>
 */
final class ChromeDownloadListController {
    private static final String DOWNLOAD_ITEM = ChromiumSemanticAnchors.DOWNLOAD_ITEM;
    private static final long[] RECONCILE_DELAYS_MS = {0L, 150L, 600L, 1800L};
    private static final int MAX_LISTS = 24;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object listLock = new Object();
    private final ArrayList<WeakReference<List<?>>> observedLists = new ArrayList<>();
    private Class<?> infoType;
    private Class<?> itemType;
    private Class<?> serviceType;

    ChromeDownloadListController(ChromiumProfile profile, ChromeRuntime runtime,
                                 HookSupport hooks, SharedPreferences prefs) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            itemType = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("download list controller unavailable: " + t.getClass().getSimpleName());
            return;
        }
        try { serviceType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE); }
        catch (Throwable ignored) { serviceType = null; }

        hookServiceList("onAllDownloadsRetrieved");
        hookServiceList("createDownloadItemList");
        hookServiceList("addDownloadItemToList");
        hookServiceItem("onDownloadItemCreated");
        hookServiceItem("onDownloadItemUpdated");
        hookAggregator("onItemsAdded");
        hookAggregator("onItemUpdated");
        hookOfflineMaterializer();

        DownloadNormalizationRegistry.setListener((oldPath, newPath) -> {
            if (!enabled()) return;
            for (long delay : RECONCILE_DELAYS_MS) {
                main.postDelayed(this::reconcileObservedLists, delay);
            }
        });
        hooks.info("download list controller installed: Chrome UI list owned by ChromeX");
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private void hookServiceList(String method) {
        if (serviceType == null || Reflect.named(serviceType, method).isEmpty()) return;
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE, method,
                "chromex:list-controller:service:" + method, chain -> {
                    if (enabled()) sanitizeArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) sanitizeList((List<?>) result);
                    return result;
                });
    }

    private void hookServiceItem(String method) {
        if (serviceType == null || Reflect.named(serviceType, method).isEmpty()) return;
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE, method,
                "chromex:list-controller:item:" + method, chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray());
                    return chain.proceed();
                });
    }

    private void hookAggregator(String method) {
        String owner = ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE;
        hooks.all(runtime.classLoader, owner, method,
                "chromex:list-controller:aggregator:" + method, chain -> {
                    if (enabled()) sanitizeArguments(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) sanitizeList((List<?>) result);
                    return result;
                });
    }

    private void hookOfflineMaterializer() {
        java.lang.reflect.Method materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);
        if (materializer == null) {
            hooks.warn("download list controller: OfflineItem materializer unresolved");
            return;
        }
        hooks.method(materializer, "chromex:list-controller:offline-item", chain -> {
            Object[] args = chain.getArgs().toArray();
            if (enabled()) rewriteArguments(args);
            Object result = chain.proceed();
            if (enabled() && result != null) rewriteObject(result);
            return result;
        });
    }

    private void sanitizeArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) sanitizeList((List<?>) arg);
            else rewriteObject(arg);
        }
    }

    private void rewriteArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) {
                for (Object value : new ArrayList<>((List<?>) arg)) rewriteObject(value);
            } else rewriteObject(arg);
        }
    }

    private void rememberList(List<?> list) {
        if (list == null) return;
        synchronized (listLock) {
            boolean found = false;
            Iterator<WeakReference<List<?>>> it = observedLists.iterator();
            while (it.hasNext()) {
                List<?> existing = it.next().get();
                if (existing == null) it.remove();
                else if (existing == list) found = true;
            }
            if (!found) observedLists.add(new WeakReference<>(list));
            while (observedLists.size() > MAX_LISTS) observedLists.remove(0);
        }
    }

    private void reconcileObservedLists() {
        ArrayList<List<?>> snapshot = new ArrayList<>();
        synchronized (listLock) {
            Iterator<WeakReference<List<?>>> it = observedLists.iterator();
            while (it.hasNext()) {
                List<?> list = it.next().get();
                if (list == null) it.remove();
                else snapshot.add(list);
            }
        }
        int touched = 0;
        for (List<?> list : snapshot) {
            int before = list.size();
            sanitizeList(list);
            if (list.size() != before) touched++;
        }
        if (touched > 0) hooks.info("download list controller reconciled lists=" + touched);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sanitizeList(List<?> list) {
        if (list == null || list.isEmpty()) return;
        rememberList(list);

        ArrayList<Object> snapshot = new ArrayList<>((List) list);
        HashMap<String, Candidate> winners = new HashMap<>();
        ArrayList<Object> remove = new ArrayList<>();
        long sequence = 0L;

        for (Object value : snapshot) {
            rewriteObject(value);
            String key = logicalKey(value);
            if (key == null) continue;
            Candidate candidate = new Candidate(value, timestamp(value), ++sequence);
            Candidate previous = winners.get(key);
            if (previous == null) {
                winners.put(key, candidate);
                continue;
            }
            Candidate winner = newer(previous, candidate);
            Candidate loser = winner == previous ? candidate : previous;
            winners.put(key, winner);
            if (!remove.contains(loser.value)) remove.add(loser.value);
        }

        if (remove.isEmpty()) return;
        int removed = 0;
        for (Object value : remove) {
            try {
                if (((List) list).remove(value)) removed++;
            } catch (Throwable ignored) {}
        }
        if (removed > 0) hooks.info("download list controller deduped rows=" + removed);
    }

    private Candidate newer(Candidate a, Candidate b) {
        if (b.time > a.time) return b;
        if (b.time < a.time) return a;
        return b.sequence >= a.sequence ? b : a;
    }

    private void rewriteObject(Object owner) {
        if (owner == null) return;
        Object info = downloadInfoFrom(owner);
        if (info == null && infoType != null && infoType.isInstance(owner)) info = owner;
        if (info == null) return;

        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
        String path = values.path;
        if (path == null || path.isBlank()) return;
        String mapped = DownloadNormalizationRegistry.resolve(path);
        if (mapped == null || mapped.equals(path)) return;
        try {
            File target = new File(mapped).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return;
            DownloadInfoAccessor.rewrite(info, profile, target);
            rewriteStrings(owner, path, values.name, target);
        } catch (Throwable ignored) {}
    }

    /** Logical key used by the visible list. */
    private String logicalKey(Object owner) {
        Object info = downloadInfoFrom(owner);
        if (info == null && infoType != null && infoType.isInstance(owner)) info = owner;
        if (info == null) return null;
        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
        String rawPath = values.path;
        if (rawPath == null || rawPath.isBlank() || !rawPath.startsWith("/")) return null;
        try {
            File raw = new File(rawPath).getCanonicalFile();
            String mapped = DownloadNormalizationRegistry.resolve(raw.getPath());
            if (mapped != null) return new File(mapped).getCanonicalPath();

            String original = DownloadNamePolicy.originalNameFromUniquified(raw.getName());
            if (original != null) {
                File base = new File(raw.getParentFile(), original).getCanonicalFile();
                // Collapse numbered variants only when a real base sibling exists. This prevents
                // an intentionally named "file (1).zip" from being merged by name alone.
                if (base.exists() && base.isFile()) return base.getCanonicalPath();
            }
            return raw.getCanonicalPath();
        } catch (Throwable ignored) {
            return rawPath;
        }
    }

    private Object downloadInfoFrom(Object owner) {
        if (owner == null || infoType == null) return null;
        if (infoType.isInstance(owner)) return owner;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !infoType.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void rewriteStrings(Object owner, String oldPath, String oldName, File target) {
        if (owner == null || target == null) return;
        String newPath = target.getAbsolutePath();
        String newName = target.getName();
        String oldBase = oldName;
        if (oldBase == null && oldPath != null) {
            try { oldBase = new File(oldPath).getName(); } catch (Throwable ignored) {}
        }
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    String replacement = null;
                    if (oldPath != null && samePath(value, oldPath)) replacement = newPath;
                    else if (oldBase != null && oldBase.equals(value)
                            && AdaptiveDownloadInfo.looksLikeFileName(value)) replacement = newName;
                    else if (oldPath != null && ("file://" + oldPath).equals(value)) {
                        replacement = "file://" + newPath;
                    }
                    if (replacement != null && !replacement.equals(value)) field.set(owner, replacement);
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
    }

    private long timestamp(Object owner) {
        if (owner == null) return Long.MIN_VALUE;
        long best = Long.MIN_VALUE;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(owner);
                    if (value > 946684800000L && value < 4102444800000L && value > best) best = value;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return best;
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null || !a.startsWith("/") || !b.startsWith("/")) return false;
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); }
        catch (Throwable ignored) { return a.equals(b); }
    }

    private static final class Candidate {
        final Object value;
        final long time;
        final long sequence;
        Candidate(Object value, long time, long sequence) {
            this.value = value;
            this.time = time;
            this.sequence = sequence;
        }
    }
}
