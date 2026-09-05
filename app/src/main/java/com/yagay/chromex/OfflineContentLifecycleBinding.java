package com.yagay.chromex;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source-of-truth download lifecycle binding backed by OfflineContentAggregatorBridge.
 *
 * <p>R8 may rename OfflineItem.state. The binding learns that field from a real state transition:
 * onItemsAdded snapshots all int fields, then onItemUpdated identifies the single field that
 * changed into OfflineItemState.COMPLETE. If it cannot identify the state safely, legacy
 * onDownloadCompleted remains the fallback.</p>
 */
final class OfflineContentLifecycleBinding {
    interface Listener {
        void onCompleted(Object offlineItem, OfflineContentLifecycleBinding source);
    }

    private static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String OFFLINE_STATE =
            "org.chromium.components.offline_items_collection.OfflineItemState";
    private static final String OPEN_PARAMS =
            "org.chromium.components.offline_items_collection.OpenParams";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final ResolvedBindings bindings;
    private final Listener listener;
    private final Map<String, Map<Field, Integer>> snapshots = new ConcurrentHashMap<>();
    private volatile Field stateField;
    private volatile Object bridgeInstance;
    private final int completeState;

    OfflineContentLifecycleBinding(ChromeRuntime runtime, HookSupport hooks,
                                   ResolvedBindings bindings, Listener listener) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.bindings = bindings;
        this.listener = listener;
        this.completeState = resolveCompleteState(runtime.classLoader);
    }

    boolean install() {
        if (bindings == null || bindings.offlineItemUpdated == null) return false;

        if (bindings.offlineItemsAdded != null) {
            hooks.method(bindings.offlineItemsAdded,
                    "chromex:offline-lifecycle:added", chain -> {
                        bridgeInstance = chain.getThisObject();
                        Object result = chain.proceed();
                        snapshotLists(chain.getArgs().toArray());
                        return result;
                    });
        }

        hooks.method(bindings.offlineItemUpdated,
                "chromex:offline-lifecycle:updated", chain -> {
                    bridgeInstance = chain.getThisObject();
                    Object item = findOfflineItem(chain.getArgs().toArray());
                    Object result = chain.proceed();
                    if (item != null) processUpdate(item);
                    return result;
                });
        hooks.info("OfflineContent lifecycle source bound: "
                + bindings.offlineItemUpdated.getDeclaringClass().getName() + '#'
                + bindings.offlineItemUpdated.getName());
        return true;
    }

    boolean open(Object offlineItem) {
        Method method = bindings == null ? null : bindings.offlineContentOpenItem;
        Object bridge = bridgeInstance;
        Object id = OfflineItemAccessor.contentId(offlineItem);
        if (method == null || bridge == null || id == null || !hasLiveNativePointer(bridge)) {
            return false;
        }
        try {
            Class<?> paramsType = Reflect.cls(runtime.classLoader, OPEN_PARAMS);
            Object params = Reflect.construct(paramsType, 0); // LaunchLocation.DOWNLOAD_HOME.
            method.invoke(bridge, params, id);
            hooks.info("download opened through OfflineContent source: "
                    + OfflineItemAccessor.contentKey(id));
            return true;
        } catch (Throwable t) {
            hooks.warn("OfflineContent openItem unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Bridge has one native pointer in stock Chromium; ambiguous long fields fail closed. */
    private static boolean hasLiveNativePointer(Object bridge) {
        if (bridge == null) return false;
        Long candidate = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (field.getName().toLowerCase().contains("native")) return value != 0L;
                    if (candidate != null) return false;
                    candidate = value;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return candidate != null && candidate != 0L;
    }

    private void snapshotLists(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (!(arg instanceof List<?>)) continue;
            for (Object value : new ArrayList<>((List<?>) arg)) {
                if (isOfflineItem(value)) snapshot(value);
            }
        }
    }

    private void processUpdate(Object item) {
        String key = key(item);
        if (key == null) return;
        Map<Field, Integer> current = intSnapshot(item);
        Map<Field, Integer> previous = snapshots.put(key, current);
        if (previous == null || current.isEmpty()) return;

        Field known = stateField;
        if (known == null) known = namedStateField(item.getClass());
        if (known != null) {
            stateField = known;
            Integer before = previous.get(known);
            Integer after = current.get(known);
            if (before != null && after != null && before.intValue() != after.intValue()
                    && after.intValue() == completeState) {
                notifyComplete(item);
            }
            return;
        }

        ArrayList<Field> candidates = new ArrayList<>();
        for (Map.Entry<Field, Integer> entry : current.entrySet()) {
            Integer old = previous.get(entry.getKey());
            if (old == null) continue;
            int now = entry.getValue();
            if (old.intValue() != now && now == completeState) candidates.add(entry.getKey());
        }
        if (candidates.size() == 1) {
            stateField = candidates.get(0);
            hooks.info("OfflineItem state field learned structurally: "
                    + stateField.getDeclaringClass().getName() + '#' + stateField.getName());
            notifyComplete(item);
        }
    }

    private void notifyComplete(Object item) {
        OfflineItemAccessor.Values values = OfflineItemAccessor.read(item);
        if (!values.usable()) return;
        try {
            listener.onCompleted(item, this);
        } catch (Throwable t) {
            hooks.error("OfflineContent completion listener", t);
        }
    }

    private void snapshot(Object item) {
        String key = key(item);
        if (key != null) snapshots.put(key, intSnapshot(item));
        if (snapshots.size() > 256) {
            int remove = snapshots.size() - 192;
            for (String old : new ArrayList<>(snapshots.keySet())) {
                snapshots.remove(old);
                if (--remove <= 0) break;
            }
        }
    }

    private String key(Object item) {
        OfflineItemAccessor.Values values = OfflineItemAccessor.read(item);
        if (values.contentKey != null) return values.contentKey;
        if (values.path != null) return "path:" + values.path;
        if (values.name != null) return "name:" + values.name;
        return null;
    }

    private static Map<Field, Integer> intSnapshot(Object item) {
        LinkedHashMap<Field, Integer> out = new LinkedHashMap<>();
        for (Field field : OfflineItemAccessor.instanceFields(item.getClass())) {
            if (field.getType() != int.class) continue;
            try { out.put(field, field.getInt(item)); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static Field namedStateField(Class<?> start) {
        Class<?> type = start;
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("state");
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return null;
    }

    private Object findOfflineItem(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (isOfflineItem(arg)) return arg;
        return null;
    }

    private boolean isOfflineItem(Object value) {
        return value != null && OFFLINE_ITEM.equals(value.getClass().getName());
    }

    private static int resolveCompleteState(ClassLoader loader) {
        try {
            Class<?> state = Reflect.cls(loader, OFFLINE_STATE);
            Field field = state.getDeclaredField("COMPLETE");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            // Chromium's generated enum has used IN_PROGRESS=0, PENDING=1, COMPLETE=2 since the
            // Offline Items API was introduced. This is only used to learn a field from a real
            // transition; ambiguous transitions are ignored and fall back to legacy completion.
            return 2;
        }
    }
}
