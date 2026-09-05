package com.yagay.chromex;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-local source of truth for files normalized after Chromium uniquifies a conflict.
 *
 * <p>Mappings are intentionally short lived. They are only used to make Chrome's Java history/UI
 * consume the exact filesystem result produced by SameNameOverwriteHooks, instead of guessing from
 * file existence or filename patterns.</p>
 */
final class DownloadNormalizationRegistry {
    interface Listener {
        void onNormalized(String oldPath, String newPath);
    }

    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MAX = 128;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static Listener listener;

    private DownloadNormalizationRegistry() {}

    static void setListener(Listener value) {
        synchronized (LOCK) {
            listener = value;
            pruneLocked(System.currentTimeMillis());
        }
    }

    static void register(File oldFile, File newFile) {
        String oldPath = canonical(oldFile);
        String newPath = canonical(newFile);
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;

        Listener notify;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            pruneLocked(now);
            ENTRIES.put(oldPath, new Entry(newPath, now));
            while (ENTRIES.size() > MAX) {
                String first = ENTRIES.keySet().iterator().next();
                ENTRIES.remove(first);
            }
            notify = listener;
        }
        if (notify != null) {
            try { notify.onNormalized(oldPath, newPath); } catch (Throwable ignored) {}
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
