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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Owns Chrome's visible download-list normalization and duplicate-row reconciliation. */
final class ChromeDownloadListController {
    private static final String DOWNLOAD_ITEM = ChromiumSemanticAnchors.DOWNLOAD_ITEM;
    private static final long[] RECONCILE_DELAYS_MS = {0L, 150L, 600L, 1800L};
    private static final int MAX_LISTS = 24;
    private static final int MAX_SEEN_ROWS = 512;
    private static final int MAX_DIAGNOSTIC_TYPES = 24;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object listLock = new Object();
    private final Object rowLock = new Object();
    private final ArrayList<WeakReference<List<?>>> observedLists = new ArrayList<>();
    private final HashMap<String, SeenRow> seenRows = new HashMap<>();
    private final Set<String> diagnosedShapes = ConcurrentHashMap.newKeySet();
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
                    Object[] args = chain.getArgs().toArray();
                    if (enabled()) {
                        diagnoseArguments(method, args);
                        sanitizeArguments(args);
                    }
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
                    if (enabled()) diagnoseArguments(method, args);
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
                    if (enabled()) {
                        diagnoseArguments("aggregator." + method, args);
                        sanitizeArguments(args);
                    }
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) sanitizeList((List<?>) result);
                    return result;
                });
    }

    private void hookOfflineMaterializer() {
        Method materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);
        if (materializer == null) {
            hooks.warn("download list controller: OfflineItem materializer unresolved");
            return;
        }
        hooks.method(materializer, "chromex:list-controller:offline-item", chain -> {
            Object[] args = chain.getArgs().toArray();
            if (enabled()) {
                diagnoseArguments("offline-materializer", args);
                rewriteArguments(args);
            }
            Object result = chain.proceed();
            if (enabled() && result != null) rewriteObject(result);
            return result;
        });
    }

    private Object firstDownloadItem(Object[] args) {
        if (args == null || itemType == null) return null;
        for (Object arg : args) if (arg != null && itemType.isInstance(arg)) return arg;
        return null;
    }

    private void trackVisibleRow(Object service, Object item, String source) {
        String key = logicalKey(item);
        String guid = downloadGuid(item);
        if (key == null || guid == null) {
            diagnoseObject(source + ":unresolved-row", item);
            return;
        }

        long time = timestamp(item);
        SeenRow loser = null;
        SeenRow winner;
        synchronized (rowLock) {
            SeenRow candidate = new SeenRow(guid, time, ++seenSequence);
            SeenRow previous = seenRows.get(key);
            if (previous == null || previous.guid.equals(guid)) {
                if (previous != null && previous.time > candidate.time) {
                    candidate = new SeenRow(guid, previous.time, candidate.sequence);
                }
                seenRows.put(key, candidate);
                trimSeenRowsLocked();
                return;
            }
            winner = newer(previous, candidate);
            loser = winner == previous ? candidate : previous;
            seenRows.put(key, winner);
            trimSeenRowsLocked();
        }

        if (loser != null && service != null) {
            try {
                Reflect.call(service, "onDownloadItemRemoved", loser.guid);
                hooks.info("download list controller removed stale row: guid=" + loser.guid
                        + " keep=" + winner.guid + " source=" + source + " key=" + key);
            } catch (Throwable t) {
                hooks.warn("download list controller stale-row notify failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private void diagnoseArguments(String source, Object[] args) {
        if (args == null) return;
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) types.append(',');
            Object arg = args[i];
            types.append(i).append('=').append(arg == null ? "null" : arg.getClass().getName());
        }
        String shape = source + '|' + types;
        if (diagnosedShapes.size() >= MAX_DIAGNOSTIC_TYPES || !diagnosedShapes.add(shape)) return;
        hooks.info("download list shape: source=" + source + " args=[" + types + "]");
        for (Object arg : args) if (arg != null) diagnoseObject(source, arg);
    }

    private void diagnoseObject(String source, Object value) {
        if (value == null) return;
        String shape = "obj|" + source + '|' + value.getClass().getName();
        if (diagnosedShapes.size() >= MAX_DIAGNOSTIC_TYPES || !diagnosedShapes.add(shape)) return;

        Object info = downloadInfoFrom(value);
        DownloadInfoAccessor.Values parsed = info == null ? null : DownloadInfoAccessor.read(info, profile);
        String guid = downloadGuid(value);
        Object contentId = callOrNull(value, "getContentId");
        Object directInfo = callOrNull(value, "getDownloadInfo");

        StringBuilder methods = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        Class<?> type = value.getClass();
        int methodBudget = 18;
        int fieldBudget = 18;
        while (type != null && type != Object.class) {
            for (Method m : type.getDeclaredMethods()) {
                if (methodBudget-- <= 0) break;
                if (methods.length() > 0) methods.append(',');
                methods.append(m.getName()).append('/').append(m.getParameterCount());
            }
            for (Field f : type.getDeclaredFields()) {
                if (fieldBudget-- <= 0) break;
                if (fields.length() > 0) fields.append(',');
                fields.append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
            if (methodBudget <= 0 && fieldBudget <= 0) break;
            type = type.getSuperclass();
        }

        hooks.info("download row inspect: source=" + source
                + " class=" + value.getClass().getName()
                + " guid=" + safe(guid)
                + " contentId=" + safeObject(contentId)
                + " directInfo=" + (directInfo == null ? "none" : directInfo.getClass().getName())
                + " nestedInfo=" + (info == null ? "none" : info.getClass().getName())
                + " name=" + (parsed == null ? "<none>" : safe(parsed.name))
                + " path=" + (parsed == null ? "<none>" : safe(parsed.path))
                + " methods=" + methods
                + " fields=" + fields);
    }

    private static Object callOrNull(Object owner, String method) {
        if (owner == null) return null;
        try { return Reflect.call(owner, method); }
        catch (Throwable ignored) { return null; }
    }

    private void trimSeenRowsLocked() {
        if (seenRows.size() > MAX_SEEN_ROWS) seenRows.clear();
    }

    private String downloadGuid(Object item) {
        Object value = callOrNull(item, "getId");
        if (value instanceof String && !((String) value).isBlank()) return (String) value;
        return null;
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
            try { if (((List) list).remove(value)) removed++; }
            catch (Throwable ignored) {}
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
        Object direct = callOrNull(owner, "getDownloadInfo");
        if (direct != null && infoType.isInstance(direct)) return direct;
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

    private static boolean samePath(String a, String b) {
        if (a == null || b == null || !a.startsWith("/") || !b.startsWith("/")) return false;
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); }
        catch (Throwable ignored) { return a.equals(b); }
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "<none>" : value; }
    private static String safeObject(Object value) {
        if (value == null) return "<none>";
        try { return value.getClass().getName() + ':' + value; }
        catch (Throwable ignored) { return value.getClass().getName(); }
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
