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

/** Universal Java history/UI reconciliation after a same-name normalization. */
final class UniversalDownloadHistoryHooks {
    private static final String DOWNLOAD_ITEM = ChromiumSemanticAnchors.DOWNLOAD_ITEM;
    private static final int MAX_RECENT_ITEMS = 192;
    private static final long[] PROPAGATE_DELAYS_MS = {0L, 250L, 900L, 2200L};

    private final ChromiumProfile profile;
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

    UniversalDownloadHistoryHooks(ChromiumProfile profile, ChromeRuntime runtime,
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
            hooks.warn("universal download history unavailable: " + t.getClass().getSimpleName());
            return;
        }
        try { serviceType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE); }
        catch (Throwable ignored) { serviceType = null; }

        DownloadNormalizationRegistry.setListener((oldPath, newPath) -> {
            if (!enabled()) return;
            for (long delay : PROPAGATE_DELAYS_MS) {
                main.postDelayed(() -> propagateNormalization(oldPath, newPath, delay), delay);
            }
        });

        hookService("onDownloadItemCreated", "created", false);
        hookService("onDownloadItemUpdated", "updated", false);
        hookService("onAllDownloadsRetrieved", "list", true);
        hookService("createDownloadItemList", "create-list", true);
        hookService("addDownloadItemToList", "add-list", true);
        hookOfflineMaterializer();
        hooks.info("universal download history hooks installed");
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private void hookService(String method, String id, boolean dedupe) {
        if (serviceType == null || Reflect.named(serviceType, method).isEmpty()) return;
        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE, method,
                "chromex:universal-history:" + id, chain -> {
                    captureService(chain.getThisObject());
                    if (enabled()) {
                        rememberArguments(chain.getArgs().toArray());
                        if (dedupe) rewriteAndDedupeArguments(chain.getArgs().toArray());
                        else rewriteArguments(chain.getArgs().toArray());
                    }
                    Object result = chain.proceed();
                    if (enabled() && result instanceof List<?>) {
                        rememberList((List<?>) result);
                        rewriteAndDedupe((List<?>) result);
                    }
                    return result;
                });
    }

    /**
     * Modern Chromium commonly R8-renames createOfflineItem. Resolve the materializer by the
     * stable signature static (DownloadItem)->OfflineItem instead of relying on the method name.
     */
    private void hookOfflineMaterializer() {
        Method materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);
        if (materializer == null) {
            hooks.warn("universal download history: OfflineItem materializer unresolved");
            return;
        }
        hooks.method(materializer, "chromex:universal-history:offline-item", chain -> {
            Object[] args = chain.getArgs().toArray();
            Object item = firstDownloadItem(args);
            if (enabled()) rememberItem(item);
            RewriteTarget target = enabled() ? targetFor(item) : null;
            if (target == null && enabled()) target = firstTarget(args);
            if (enabled()) rewriteArguments(args);
            Object result = chain.proceed();
            if (enabled() && result != null) {
                if (target != null) rewriteStrings(result, target);
                else rewriteOfflineByValue(result);
            }
            return result;
        });
        hooks.info("universal download history OfflineItem materializer="
                + materializer.getDeclaringClass().getName() + '#' + materializer.getName());
    }

    private Object firstDownloadItem(Object[] args) {
        if (args == null || itemType == null) return null;
        for (Object arg : args) if (arg != null && itemType.isInstance(arg)) return arg;
        return null;
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
            current = Reflect.callStatic(serviceType, "getDownloadManagerService");
            if (current != null) serviceInstance = current;
        } catch (Throwable ignored) {}
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
                if (existing == null) iterator.remove();
                else if (existing == item) found = true;
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
            hooks.info("download history propagated normalization: "
                    + target.oldName + " -> " + target.target.getName()
                    + " items=" + rewritten + " redispatched=" + redispatched
                    + " delay=" + delay);
        }
    }

    private boolean redispatchUpdatedItem(Object item) {
        Object service = currentService();
        if (service == null || item == null) return false;
        try {
            Reflect.call(service, "onDownloadItemUpdated", item);
            return true;
        } catch (Throwable ignored) {
            return false;
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

    private void rewriteArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteList((List<?>) arg);
            else rewriteByRegistry(arg);
        }
    }

    private void rewriteAndDedupeArguments(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) rewriteAndDedupe((List<?>) arg);
            else rewriteByRegistry(arg);
        }
    }

    private void rewriteList(List<?> list) {
        if (list == null) return;
        for (Object value : new ArrayList<>(list)) rewriteByRegistry(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void rewriteAndDedupe(List<?> list) {
        if (list == null || list.size() < 1) return;
        rewriteList(list);
        HashMap<String, Object> winners = new HashMap<>();
        ArrayList<Object> remove = new ArrayList<>();
        for (Object value : new ArrayList<>(list)) {
            String key = logicalPath(value);
            if (key == null) continue;
            Object previous = winners.get(key);
            if (previous == null) {
                winners.put(key, value);
                continue;
            }
            Object winner = newer(previous, value);
            Object loser = winner == previous ? value : previous;
            winners.put(key, winner);
            remove.add(loser);
        }
        if (remove.isEmpty()) return;
        try {
            ((List) list).removeAll(remove);
            hooks.info("download history deduped rows=" + remove.size());
        } catch (Throwable t) {
            hooks.warn("download history dedupe skipped: " + t.getClass().getSimpleName());
        }
    }

    private Object newer(Object a, Object b) {
        long ta = timestamp(a);
        long tb = timestamp(b);
        return tb > ta ? b : a;
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

    private void rewriteByRegistry(Object value) {
        if (value == null) return;
        Object item = asDownloadItem(value);
        if (item != null) rememberItem(item);
        RewriteTarget target = targetFor(value);
        if (target != null) rewriteTarget(value, target);
    }

    private RewriteTarget targetFor(Object owner) {
        if (owner == null) return null;
        Object info = downloadInfoFrom(owner);
        if (info == null && infoType != null && infoType.isInstance(owner)) info = owner;
        String path = infoPath(info);
        if (path == null) path = firstAbsolutePath(owner);
        if (path == null) return null;
        String mapped = DownloadNormalizationRegistry.resolve(path);
        if (mapped == null || mapped.equals(path)) return restartFallback(path);
        try {
            File target = new File(mapped).getCanonicalFile();
            File old = new File(path).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return null;
            return new RewriteTarget(old.getAbsolutePath(), old.getName(), target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private RewriteTarget restartFallback(String path) {
        try {
            File old = new File(path).getCanonicalFile();
            if (old.exists()) return null;
            String base = DownloadNamePolicy.originalNameFromUniquified(old.getName());
            if (base == null) return null;
            File target = new File(old.getParentFile(), base).getCanonicalFile();
            if (!target.exists() || !target.isFile()) return null;
            DownloadNormalizationRegistry.register(old, target);
            return new RewriteTarget(old.getAbsolutePath(), old.getName(), target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private RewriteTarget firstTarget(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            RewriteTarget target = targetFor(arg);
            if (target != null) return target;
        }
        return null;
    }

    private void rewriteOfflineByValue(Object owner) {
        if (owner == null) return;
        String path = firstAbsolutePath(owner);
        if (path == null) return;
        String mapped = DownloadNormalizationRegistry.resolve(path);
        if (mapped == null || mapped.equals(path)) return;
        RewriteTarget target = exactTarget(path, mapped);
        if (target != null) rewriteStrings(owner, target);
    }

    private void rewriteTarget(Object owner, RewriteTarget target) {
        if (owner == null || target == null) return;
        if (infoType != null && infoType.isInstance(owner)) {
            DownloadInfoAccessor.rewrite(owner, profile, target.target);
        }
        Object info = downloadInfoFrom(owner);
        if (info != null) DownloadInfoAccessor.rewrite(info, profile, target.target);
        rewriteStrings(owner, target);
    }

    private void rewriteStrings(Object owner, RewriteTarget target) {
        if (owner == null || target == null) return;
        String newPath = target.target.getAbsolutePath();
        String newName = target.target.getName();
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
                    if (samePath(value, target.oldPath)) replacement = newPath;
                    else if (target.oldName.equals(value)) replacement = newName;
                    else if (("file://" + target.oldPath).equals(value)) replacement = "file://" + newPath;
                    if (replacement != null && !replacement.equals(value)) field.set(owner, replacement);
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
    }

    private boolean containsOldValue(Object owner, RewriteTarget target) {
        if (owner == null || target == null) return false;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (raw instanceof String) {
                        String value = (String) raw;
                        if (samePath(value, target.oldPath) || target.oldName.equals(value)) return true;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private Object asDownloadItem(Object value) {
        if (value == null || itemType == null) return null;
        if (itemType.isInstance(value)) return value;
        return null;
    }

    private Object downloadInfoFrom(Object owner) {
        if (owner == null || infoType == null) return null;
        if (infoType.isInstance(owner)) return owner;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !infoType.isAssignableFrom(field.getType())) continue;
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

    private String infoPath(Object info) {
        return DownloadInfoAccessor.read(info, profile).path;
    }

    private String firstAbsolutePath(Object owner) {
        if (owner == null) return null;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (raw instanceof String && ((String) raw).startsWith("/")) return (String) raw;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private String logicalPath(Object owner) {
        Object info = downloadInfoFrom(owner);
        String path = infoPath(info);
        if (path == null) path = firstAbsolutePath(owner);
        if (path == null) return null;
        String mapped = DownloadNormalizationRegistry.resolve(path);
        return mapped == null ? path : mapped;
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); }
        catch (Throwable ignored) { return a.equals(b); }
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
}
