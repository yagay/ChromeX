package com.yagay.chromex;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * Semantic binding to Chromium's OfflineContentProvider::RenameItem source-of-truth operation.
 * Works across generated semantic JNI forks and stock Chromium's compressed J.N trampoline.
 */
final class OfflineContentRenameBinding {
    private static final String BRIDGE = ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE;
    private static final String CALLBACK = "org.chromium.base.Callback";
    private static final String SEMANTIC = ChromiumSemanticAnchors.OFFLINE_RENAME_SEMANTIC;
    private static final long RECORD_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int MAX_RECORDS = 256;
    private static final Map<String, NativeCall> NATIVE_CACHE = new ConcurrentHashMap<>();

    interface ResultCallback {
        void onResult(boolean success, int resultCode, String source);
    }

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final AtomicReference<WeakReference<Object>> bridgeRef =
            new AtomicReference<>(new WeakReference<>(null));
    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    private Class<?> infoType;
    private Class<?> itemType;
    private Class<?> bridgeType;
    private Class<?> callbackType;
    private Method materializer;
    private Method semanticNative;
    private NativeCall compressedNative;

    OfflineContentRenameBinding(ChromiumProfile profile, ChromeRuntime runtime, HookSupport hooks) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void install() {
        try {
            infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            itemType = Reflect.cls(runtime.classLoader, ChromiumSemanticAnchors.DOWNLOAD_ITEM);
            bridgeType = Reflect.cls(runtime.classLoader, BRIDGE);
            callbackType = Reflect.cls(runtime.classLoader, CALLBACK);
        } catch (Throwable t) {
            hooks.warn("offline rename binding unavailable: " + t.getClass().getSimpleName());
            return;
        }

        semanticNative = resolveSemanticNative();
        if (semanticNative == null) compressedNative = resolveCompressedNative();
        materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);

        hookBridgeFactory();
        hookDownloadItems();
        hookMaterializer();
        hooks.info("offline rename binding installed: backend=" + backendLabel()
                + " materializer=" + (materializer == null ? "none" : methodLabel(materializer)));
    }

    boolean available() {
        return bridgeType != null && callbackType != null
                && (semanticNative != null || compressedNative != null);
    }

    String backendLabel() {
        if (semanticNative != null) return "semantic-jni:" + methodLabel(semanticNative);
        if (compressedNative != null) {
            return "structural-jni:" + methodLabel(compressedNative.method)
                    + " selector=" + compressedNative.selector;
        }
        return "unavailable";
    }

    boolean rename(String path, String name, String newName, ResultCallback callback) {
        if (!available() || newName == null || newName.isBlank()) return false;
        Record record = findRecord(path, name);
        if (record == null) {
            hooks.warn("offline rename record unavailable: name=" + safe(name)
                    + " path=" + safe(path));
            return false;
        }
        Object bridge = currentBridge();
        if (bridge == null) {
            hooks.warn("offline rename bridge instance unavailable");
            return false;
        }
        long ptr = nativePtr(bridge);
        if (ptr == 0L) {
            hooks.warn("offline rename native pointer unavailable");
            return false;
        }
        Object proxy = callbackProxy(callback);
        if (proxy == null) return false;

        try {
            if (semanticNative != null) {
                Class<?>[] p = semanticNative.getParameterTypes();
                if (p.length == 6 && p[0] == long.class) {
                    semanticNative.invoke(null, ptr, bridge, record.namespace, record.id, newName, proxy);
                    hooks.info("offline source rename requested via semantic JNI: "
                            + safe(name) + " -> " + newName + " id=" + shortId(record.id));
                    return true;
                }
            }
            NativeCall call = compressedNative;
            if (call != null) {
                call.method.invoke(null, call.selector, ptr,
                        record.namespace, record.id, newName, proxy);
                hooks.info("offline source rename requested via structural JNI: "
                        + safe(name) + " -> " + newName + " id=" + shortId(record.id)
                        + " selector=" + call.selector);
                return true;
            }
        } catch (Throwable t) {
            hooks.warn("offline source rename invocation failed: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private Method resolveSemanticNative() {
        Method method = AdaptiveDexResolver.resolveSemanticNative(runtime, hooks, SEMANTIC);
        if (method == null) return null;
        Class<?>[] p = method.getParameterTypes();
        if (!Modifier.isStatic(method.getModifiers()) || p.length != 6 || p[0] != long.class) {
            hooks.warn("offline rename semantic JNI rejected unexpected signature: " + methodLabel(method));
            return null;
        }
        return method;
    }

    /** Stock Chromium strips generated semantic names; recover the native call from its data flow. */
    private NativeCall resolveCompressedNative() {
        String cacheKey = runtime.resolverCacheKey();
        NativeCall memory = NATIVE_CACHE.get(cacheKey);
        if (memory != null) return memory;

        for (String path : runtime.dexPaths()) {
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                for (MethodData data : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().returnType("void")))) {
                    Method caller;
                    try { caller = data.getMethodInstance(runtime.classLoader); }
                    catch (Throwable ignored) { continue; }
                    Class<?>[] cp = caller.getParameterTypes();
                    if (cp.length != 2 || cp[0] != String.class
                            || !callbackType.isAssignableFrom(cp[1])) continue;

                    String fields = String.valueOf(data.getUsingFields());
                    if (!fields.contains("OfflineContentAggregatorBridge")
                            || !fields.contains("OfflineItem")) continue;

                    for (MethodData invoked : data.getInvokes()) {
                        if (!"J.N".equals(invoked.getClassName())
                                || !"void".equals(invoked.getReturnTypeName())) continue;
                        List<String> p = invoked.getParamTypeNames();
                        if (!isCompressedRenameSignature(p)) continue;
                        Integer selector = DexNativeSelectorResolver.resolve(
                                path, data.getDescriptor(), invoked.getDescriptor());
                        if (selector == null) continue;
                        Method nativeMethod;
                        try { nativeMethod = invoked.getMethodInstance(runtime.classLoader); }
                        catch (Throwable ignored) { continue; }
                        NativeCall resolved = new NativeCall(nativeMethod, selector);
                        NATIVE_CACHE.put(cacheKey, resolved);
                        hooks.info("offline rename structural JNI resolved: caller="
                                + methodLabel(caller) + " -> " + methodLabel(nativeMethod)
                                + " selector=" + selector);
                        return resolved;
                    }
                }
            } catch (Throwable ignored) {}
        }
        hooks.warn("offline rename structural JNI unresolved");
        return null;
    }

    private static boolean isCompressedRenameSignature(List<String> p) {
        if (p == null || p.size() != 6) return false;
        return "int".equals(p.get(0)) && "long".equals(p.get(1))
                && "java.lang.Object".equals(p.get(2))
                && "java.lang.Object".equals(p.get(3))
                && "java.lang.Object".equals(p.get(4))
                && "java.lang.Object".equals(p.get(5));
    }

    private void hookBridgeFactory() {
        if (Reflect.named(bridgeType, "create").isEmpty()) return;
        hooks.all(runtime.classLoader, BRIDGE, "create", "chromex:offline-rename:bridge-create", chain -> {
            Object result = chain.proceed();
            rememberBridge(result);
            return result;
        });
    }

    private void hookDownloadItems() {
        String service = Chrome145.DOWNLOAD_MANAGER_SERVICE;
        for (String method : new String[]{"onDownloadItemCreated", "onDownloadItemUpdated",
                "addDownloadItemToList"}) {
            try {
                Class<?> type = Reflect.cls(runtime.classLoader, service);
                if (Reflect.named(type, method).isEmpty()) continue;
                hooks.all(runtime.classLoader, service, method,
                        "chromex:offline-rename:item:" + method, chain -> {
                            for (Object arg : chain.getArgs().toArray()) captureItem(arg);
                            Object result = chain.proceed();
                            if (result instanceof List<?>) {
                                for (Object item : (List<?>) result) captureItem(item);
                            }
                            return result;
                        });
            } catch (Throwable ignored) {}
        }
    }

    private void hookMaterializer() {
        if (materializer == null) return;
        hooks.method(materializer, "chromex:offline-rename:materializer", chain -> {
            Object item = chain.getArgs().isEmpty() ? chain.getThisObject() : chain.getArg(0);
            captureItem(item);
            Object result = chain.proceed();
            captureOffline(item, result);
            return result;
        });
    }

    private void captureOffline(Object item, Object offline) {
        if (item == null || offline == null) return;
        ContentRef ref = contentRef(offline);
        if (ref == null) ref = contentRef(item);
        storeRecord(item, ref);
    }

    private void captureItem(Object value) {
        if (value == null || itemType == null || !itemType.isInstance(value)) return;
        storeRecord(value, contentRef(value));
    }

    private void storeRecord(Object item, ContentRef ref) {
        if (item == null || ref == null) return;
        Object info = Reflect.findFieldValueByType(item, infoType);
        if (info == null) return;
        DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
        if (!values.usable()) return;
        long now = System.currentTimeMillis();
        Record record = new Record(ref.namespace, ref.id, values.path, values.name, now);
        String pathKey = pathKey(values.path);
        if (pathKey != null) records.put("p:" + pathKey, record);
        if (values.name != null && !values.name.isBlank()) records.put("n:" + values.name, record);
        prune(now);
    }

    private Record findRecord(String path, String name) {
        long now = System.currentTimeMillis();
        prune(now);
        String key = pathKey(path);
        Record record = key == null ? null : records.get("p:" + key);
        if (record == null && name != null) record = records.get("n:" + name);
        if (record == null && path != null) {
            String base = DownloadNamePolicy.fileNameOnly(path);
            if (base != null) record = records.get("n:" + base);
        }
        return record != null && now - record.time <= RECORD_TTL_MS ? record : null;
    }

    private void prune(long now) {
        if (records.size() <= MAX_RECORDS) {
            records.entrySet().removeIf(e -> now - e.getValue().time > RECORD_TTL_MS);
            return;
        }
        records.entrySet().removeIf(e -> now - e.getValue().time > RECORD_TTL_MS / 2);
        if (records.size() <= MAX_RECORDS) return;
        ArrayList<Map.Entry<String, Record>> entries = new ArrayList<>(records.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().time, b.getValue().time));
        for (int i = 0; i < entries.size() - MAX_RECORDS; i++) records.remove(entries.get(i).getKey());
    }

    private void rememberBridge(Object value) {
        if (value != null && bridgeType != null && bridgeType.isInstance(value)) {
            bridgeRef.set(new WeakReference<>(value));
        }
    }

    private Object currentBridge() {
        WeakReference<Object> ref = bridgeRef.get();
        Object bridge = ref == null ? null : ref.get();
        return bridge != null && nativePtr(bridge) != 0L ? bridge : null;
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    long value = field.getLong(bridge);
                    if (value == 0L) continue;
                    if (found != null) return 0L;
                    found = field;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try { return found.getLong(bridge); } catch (Throwable ignored) { return 0L; }
    }

    private Object callbackProxy(ResultCallback callback) {
        try {
            return Proxy.newProxyInstance(runtime.classLoader, new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onResult".equals(name)) {
                            int code = args != null && args.length > 0 && args[0] instanceof Number
                                    ? ((Number) args[0]).intValue() : -1;
                            if (callback != null) callback.onResult(code == 0, code, backendLabel());
                            return null;
                        }
                        if ("toString".equals(name)) return "ChromeXOfflineRenameCallback";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return args != null && args.length > 0 && proxy == args[0];
                        return null;
                    });
        } catch (Throwable t) {
            hooks.warn("offline rename callback proxy unavailable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Find an R8-obfuscated ContentId by its stable two-string value structure. */
    private ContentRef contentRef(Object owner) {
        if (owner == null) return null;
        ContentRef best = null;
        int bestScore = Integer.MIN_VALUE;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || field.getType() == String.class) continue;
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(owner);
                    ContentRef ref = candidateContentRef(candidate);
                    if (ref != null && ref.score > bestScore) {
                        best = ref;
                        bestScore = ref.score;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return best;
    }

    private ContentRef candidateContentRef(Object value) {
        if (value == null) return null;
        ArrayList<NamedString> strings = new ArrayList<>();
        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(value);
                    if (raw instanceof String && !((String) raw).isBlank()) {
                        strings.add(new NamedString(field.getName(), (String) raw));
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        if (strings.size() != 2) return null;
        NamedString first = strings.get(0), second = strings.get(1);
        NamedString id = chooseId(first, second);
        NamedString namespace = id == first ? second : first;
        if (namespace.value.contains("/") || namespace.value.contains("://")
                || id.value.contains("/") || id.value.contains("://")) return null;
        int score = 20;
        if (looksGuid(id.value)) score += 80;
        if (id.name.toLowerCase(Locale.ROOT).contains("id")) score += 30;
        if (namespace.name.toLowerCase(Locale.ROOT).contains("namespace")) score += 30;
        if (namespace.value.length() <= 48) score += 10;
        return new ContentRef(namespace.value, id.value, score);
    }

    private static NamedString chooseId(NamedString a, NamedString b) {
        if (looksGuid(a.value) && !looksGuid(b.value)) return a;
        if (looksGuid(b.value) && !looksGuid(a.value)) return b;
        String an = a.name.toLowerCase(Locale.ROOT), bn = b.name.toLowerCase(Locale.ROOT);
        if (an.equals("id") || an.endsWith("id")) return a;
        if (bn.equals("id") || bn.endsWith("id")) return b;
        return b;
    }

    private static boolean looksGuid(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() == 36 && v.charAt(8) == '-' && v.charAt(13) == '-'
                && v.charAt(18) == '-' && v.charAt(23) == '-') return true;
        return v.length() >= 24 && v.matches("[0-9a-fA-F_-]+");
    }

    private static String pathKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (!raw.startsWith("/") && !raw.startsWith("file://")) return raw;
        String path = raw.startsWith("file://") ? raw.substring(7) : raw;
        try { return new File(path).getCanonicalPath(); }
        catch (Throwable ignored) { return path; }
    }

    private static String methodLabel(Method method) {
        return method == null ? "none" : method.getDeclaringClass().getName() + '#' + method.getName();
    }

    private static String shortId(String value) {
        if (value == null) return "<none>";
        return value.length() <= 12 ? value : value.substring(0, 8) + "…";
    }

    private static String safe(String value) {
        if (value == null) return "<none>";
        String name = DownloadNamePolicy.fileNameOnly(value);
        return name == null ? "<value>" : name;
    }

    private static final class NativeCall {
        final Method method;
        final int selector;
        NativeCall(Method method, int selector) {
            this.method = method;
            this.selector = selector;
            try { method.setAccessible(true); } catch (Throwable ignored) {}
        }
    }

    private static final class ContentRef {
        final String namespace, id;
        final int score;
        ContentRef(String namespace, String id, int score) {
            this.namespace = namespace;
            this.id = id;
            this.score = score;
        }
    }

    private static final class NamedString {
        final String name, value;
        NamedString(String name, String value) { this.name = name; this.value = value; }
    }

    private static final class Record {
        final String namespace, id, path, name;
        final long time;
        Record(String namespace, String id, String path, String name, long time) {
            this.namespace = namespace;
            this.id = id;
            this.path = path;
            this.name = name;
            this.time = time;
        }
    }
}
