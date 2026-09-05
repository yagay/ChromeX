package com.yagay.chromex;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local source of truth for files normalized after Chromium uniquifies a conflict.
 *
 * <p>Mappings are intentionally short lived. Feature bindings may independently subscribe to a
 * normalization event (for example Java history reconciliation and a legacy download-backend
 * refresh) without replacing one another.</p>
 */
final class DownloadNormalizationRegistry {
    interface Listener {
        void onNormalized(String oldPath, String newPath);
    }

    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MAX = 128;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final ArrayList<Listener> LISTENERS = new ArrayList<>();

    private DownloadNormalizationRegistry() {}

    /**
     * Historical API name kept for source compatibility. Listeners are additive so independent
     * capability bindings cannot silently disable each other.
     */
    static void setListener(Listener value) {
        if (value == null) return;
        synchronized (LOCK) {
            for (Listener existing : LISTENERS) {
                if (existing == value) return;
            }
            LISTENERS.add(value);
            pruneLocked(System.currentTimeMillis());
        }
    }

    static void register(File oldFile, File newFile) {
        String oldPath = canonical(oldFile);
        String newPath = canonical(newFile);
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;

        List<Listener> notify;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            pruneLocked(now);
            ENTRIES.put(oldPath, new Entry(newPath, now));
            while (ENTRIES.size() > MAX) {
                String first = ENTRIES.keySet().iterator().next();
                ENTRIES.remove(first);
            }
            notify = new ArrayList<>(LISTENERS);
        }
        for (Listener listener : notify) {
            try { listener.onNormalized(oldPath, newPath); } catch (Throwable ignored) {}
        }
    }

    static String resolve(String rawPath) {
        String key = canonical(rawPath);
        if (key == null) return null;
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            Entry entry = ENTRIES.get(key);
            return entry == null ? null : entry.newPath;
        }
    }

    static String logicalPath(String rawPath) {
        String canonical = canonical(rawPath);
        if (canonical == null) return null;
        String mapped = resolve(canonical);
        return mapped == null ? canonical : mapped;
    }

    static Map<String, String> snapshot() {
        synchronized (LOCK) {
            pruneLocked(System.currentTimeMillis());
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().newPath);
            }
            return copy;
        }
    }

    private static void pruneLocked(long now) {
        ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().time > TTL_MS);
    }

    private static String canonical(File file) {
        if (file == null) return null;
        try { return file.getCanonicalPath(); }
        catch (Throwable ignored) { return null; }
    }

    private static String canonical(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        return canonical(new File(path));
    }

    private static final class Entry {
        final String newPath;
        final long time;

        Entry(String newPath, long time) {
            this.newPath = newPath;
            this.time = time;
        }
    }
}
