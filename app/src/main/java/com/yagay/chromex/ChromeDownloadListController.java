package com.yagay.chromex;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/** Owns Chrome's visible download-list normalization and duplicate-row reconciliation. */
final class ChromeDownloadListController {
    private static final String DOWNLOAD_ITEM = ChromiumSemanticAnchors.DOWNLOAD_ITEM;
    private static final long[] RECONCILE_DELAYS_MS = {0L, 150L, 600L, 1800L};
    private static final int MAX_LISTS = 24;
    private static final int MAX_SEEN_ROWS = 512;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object listLock = new Object();
    private final Object rowLock = new Object();
    private final ArrayList<WeakReference<List<?>>> observedLists = new ArrayList<>();
    private final HashMap<String, SeenRow> seenRows = new HashMap<>();
    private long seenSequence;
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
        try {
            serviceType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
        } catch (Throwable ignored) {
            serviceType = null;
        }

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
        hooks.info("download list controller installed: exact152=" + exact152());
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private boolean exact152() {
        return Chrome152.VERIFIED_VERSION.equals(runtime.versionName);
    }

    private void hookServiceList(String method) {
        if (serviceType == null || Reflect.named(serviceType, method).isEmpty()) return;
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE, method,
                "chromex:list-controller:service:" + method, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (enabled()) sanitizeArguments(args);
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) sanitizeList((List<?>) result);
                    return result;
                });
    }

    private void hookServiceItem(String method) {
        if (serviceType == null || Reflect.named(serviceType, method).isEmpty()) return;
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE, method,
                "chromex:list-controller:item:" + method, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    Object item = enabled() ? firstDownloadItem(args) : null;
                    if (enabled()) rewriteArguments(args);
                    Object result = chain.proceed();
                    if (enabled() && item != null) trackVisibleRow(chain.getThisObject(), item, method);
                    return result;
                });
    }

    private void hookAggregator(String method) {
        String owner = ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE;
        hooks.all(runtime.classLoader, owner, method,
                "chromex:list-controller:aggregator:" + method, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (enabled()) sanitizeArguments(args);
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) sanitizeList((List<?>) result);
                    return result;
                });
    }

    private void hookOfflineMaterializer() {
        Method materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);
        if (materializer == null) return;
        hooks.method(materializer, "chromex:list-controller:offline-item", chain -> {
            Object[] args = chain.getArgs().toArray();
            if (enabled()) rewriteArguments(args);
            Object result = chain.proceed();
            if (enabled() && result != null) rewriteObject(result);
            return result;
        });
    }

    private Object firstDownloadItem(Object[] args) {
        if (args == null || itemType == null) return null;
        for (Object arg : args) {
            if (arg != null && itemType.isInstance(arg)) return arg;
        }
        return null;
    }

    private void trackVisibleRow(Object service, Object item, String source) {
        RowIdentity row = rowIdentity(item);
        if (row == null || service == null) return;

        SeenRow loser = null;
        SeenRow winner;
        synchronized (rowLock) {
            SeenRow candidate = new SeenRow(row.guid, row.time, ++seenSequence);
            SeenRow previous = seenRows.get(row.key);
            if (previous == null || previous.guid.equals(row.guid)) {
                seenRows.put(row.key, candidate);
                trimSeenRowsLocked();
                return;
            }
            winner = newer(previous, candidate);
            loser = winner == previous ? candidate : previous;
            seenRows.put(row.key, winner);
            trimSeenRowsLocked();
        }

        if (loser != null) {
            try {
                Reflect.call(service, "onDownloadItemRemoved", loser.guid);
                hooks.info("download list controller removed stale row: guid=" + loser.guid
                        + " keep=" + winner.guid + " source=" + source + " key=" + row.key);
            } catch (Throwable t) {
                hooks.warn("download list controller stale-row notify failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private RowIdentity rowIdentity(Object item) {
        if (item == null) return null;
        String guid = downloadGuid(item);
        Object info = downloadInfoFrom(item);
        DownloadValues values = readValues(info);
        if (guid == null || values == null) return null;

        String key = logicalKey(values.path, values.name);
        if (key == null) return null;
        return new RowIdentity(guid, key, timestamp(item));
    }

    private String downloadGuid(Object item) {
        Object value = callOrNull(item, "getId");
        if (value instanceof String && !((String) value).isBlank()) return (String) value;

        // Chrome 152.0.7977.75 R8 maps DownloadItem.getId() to the sole zero-arg method a().
        if (exact152()) {
            value = callOrNull(item, "a");
            if (value instanceof String && !((String) value).isBlank()) return (String) value;
        }
        return null;
    }

    private DownloadValues readValues(Object info) {
        if (info == null) return null;

        if (exact152()) {
            String name = stringField(info, Chrome152.DOWNLOAD_INFO_NAME);
            String path = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);
            if (name != null || path != null) {
                if (name == null && path != null && path.startsWith("/")) {
                    try { name = new File(path).getName(); } catch (Throwable ignored) {}
                }
                return new DownloadValues(path, name);
            }
        }

        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
        if (values.path == null && values.name == null) return null;
        return new DownloadValues(values.path, values.name);
    }

    private String logicalKey(String rawPath, String rawName) {
        if (rawPath != null && rawPath.startsWith("/")) {
            try {
                File raw = new File(rawPath).getCanonicalFile();
                String mapped = DownloadNormalizationRegistry.resolve(raw.getPath());
                if (mapped != null) return new File(mapped).getCanonicalPath();

                String original = DownloadNamePolicy.originalNameFromUniquified(raw.getName());
                if (original != null) {
                    File base = new File(raw.getParentFile(), original).getCanonicalFile();
                    if (base.exists() && base.isFile()) return base.getCanonicalPath();
                }
                return raw.getCanonicalPath();
            } catch (Throwable ignored) {
                return rawPath;
            }
        }

        String name = rawName;
        if (name == null || name.isBlank()) return null;
        String original = DownloadNamePolicy.originalNameFromUniquified(name);
        if (original != null) name = original;
        return "name:" + name;
    }

    private void trimSeenRowsLocked() {
        if (seenRows.size() > MAX_SEEN_ROWS) seenRows.clear();
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
            } else {
                rewriteObject(arg);
            }
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
            RowIdentity row = rowIdentity(value);
            if (row == null) continue;

            synchronized (rowLock) {
                SeenRow old = seenRows.get(row.key);
                SeenRow candidate = new SeenRow(row.guid, row.time, ++seenSequence);
                if (old == null || newer(old, candidate) == candidate) seenRows.put(row.key, candidate);
                trimSeenRowsLocked();
            }

            Candidate candidate = new Candidate(value, row.time, ++sequence);
            Candidate previous = winners.get(row.key);
            if (previous == null) {
                winners.put(row.key, candidate);
                continue;
            }
            Candidate winner = newer(previous, candidate);
            Candidate loser = winner == previous ? candidate : previous;
            winners.put(row.key, winner);
            if (!remove.contains(loser.value)) remove.add(loser.value);
        }

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

    private SeenRow newer(SeenRow a, SeenRow b) {
        if (b.time > a.time) return b;
        if (b.time < a.time) return a;
        return b.sequence >= a.sequence ? b : a;
    }

    private void rewriteObject(Object owner) {
        if (owner == null) return;
        Object info = downloadInfoFrom(owner);
        if (info == null && infoType != null && infoType.isInstance(owner)) info = owner;
        if (info == null) return;

        DownloadValues values = readValues(info);
        if (values == null || values.path == null || values.path.isBlank()) return;
        String mapped = DownloadNormalizationRegistry.resolve(values.path);
        if (mapped == null || mapped.equals(values.path)) return;
        try {
            File target = new File(mapped).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return;
            DownloadInfoAccessor.rewrite(info, profile, target);
            rewriteStrings(owner, values.path, values.name, target);
        } catch (Throwable ignored) {}
    }

    private Object downloadInfoFrom(Object owner) {
        if (owner == null || infoType == null) return null;
        if (infoType.isInstance(owner)) return owner;

        Object direct = callOrNull(owner, "getDownloadInfo");
        if (direct != null && infoType.isInstance(direct)) return direct;

        // Chrome 152 exact R8 layout: DownloadItem.c is DownloadInfo.
        if (exact152() && itemType != null && itemType.isInstance(owner)) {
            try {
                Object exact = Reflect.get(owner, "c");
                if (exact != null && infoType.isInstance(exact)) return exact;
            } catch (Throwable ignored) {}
        }

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
                    else if (oldPath != null && ("file://" + oldPath).equals(value)) replacement = "file://" + newPath;
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

    private static Object callOrNull(Object owner, String method) {
        if (owner == null) return null;
        try { return Reflect.call(owner, method); }
        catch (Throwable ignored) { return null; }
    }

    private static String stringField(Object owner, String name) {
        if (owner == null || name == null) return null;
        try {
            Object value = Reflect.get(owner, name);
            return value instanceof String && !((String) value).isBlank() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null || !a.startsWith("/") || !b.startsWith("/")) return false;
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); }
        catch (Throwable ignored) { return a.equals(b); }
    }

    private static final class DownloadValues {
        final String path;
        final String name;
        DownloadValues(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }

    private static final class RowIdentity {
        final String guid;
        final String key;
        final long time;
        RowIdentity(String guid, String key, long time) {
            this.guid = guid;
            this.key = key;
            this.time = time;
        }
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

    private static final class SeenRow {
        final String guid;
        final long time;
        final long sequence;
        SeenRow(String guid, long time, long sequence) {
            this.guid = guid;
            this.time = time;
            this.sequence = sequence;
        }
    }
}
