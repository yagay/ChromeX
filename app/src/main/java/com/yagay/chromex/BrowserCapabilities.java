package com.yagay.chromex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime capability map for one Chromium-family browser build.
 *
 * <p>Feature code should depend on semantic capabilities instead of package names, app versions,
 * or R8 symbols. Exact Chrome builds simply contribute high-confidence bindings to this map;
 * unknown/vendor forks are resolved from stable types, signatures, Dex semantics and live object
 * structure.</p>
 */
final class BrowserCapabilities {
    enum Key {
        CORE_RUNTIME,
        TABBED_ACTIVITY,
        GURL,
        PROFILE,
        PREF_SERVICE,
        TAB_MODEL,
        TAB_CREATOR,
        HOMEPAGE,
        RESTORE_CONTROL,
        DOWNLOAD_INFO,
        DOWNLOAD_COMPLETION,
        DOWNLOAD_DUPLICATE_CONFLICT,
        DOWNLOAD_CONFLICT_POLICY,
        DOWNLOAD_HISTORY,
        DOWNLOAD_OFFLINE_UI,
        DOWNLOAD_RENAME,
        DOWNLOAD_OPEN,
        DOWNLOAD_LOCATION_DIALOG,
        TRANSLATE_MESSAGE
    }

    enum Source {
        VERIFIED_EXACT,
        STABLE_API,
        GENERATED_JNI,
        SEMANTIC_DEX,
        STRUCTURAL,
        LIVE_RUNTIME,
        UNAVAILABLE
    }

    static final class Entry {
        final Key key;
        final boolean available;
        final Source source;
        final int confidence;
        final String detail;

        Entry(Key key, boolean available, Source source, int confidence, String detail) {
            this.key = key;
            this.available = available;
            this.source = source == null ? Source.UNAVAILABLE : source;
            this.confidence = Math.max(0, Math.min(100, confidence));
            this.detail = detail == null ? "" : detail;
        }

        String compact() {
            return key.name().toLowerCase(Locale.ROOT) + '='
                    + (available ? "yes" : "no") + ':' + source.name().toLowerCase(Locale.ROOT)
                    + ':' + confidence + (detail.isBlank() ? "" : ':' + detail);
        }
    }

    static final class Builder {
        private final EnumMap<Key, Entry> values = new EnumMap<>(Key.class);

        Builder available(Key key, Source source, int confidence, String detail) {
            values.put(key, new Entry(key, true, source, confidence, detail));
            return this;
        }

        Builder unavailable(Key key, String detail) {
            values.put(key, new Entry(key, false, Source.UNAVAILABLE, 0, detail));
            return this;
        }

        BrowserCapabilities build() {
            for (Key key : Key.values()) {
                values.putIfAbsent(key, new Entry(key, false, Source.UNAVAILABLE, 0, "not resolved"));
            }
            return new BrowserCapabilities(values);
        }
    }

    private final EnumMap<Key, Entry> entries;

    private BrowserCapabilities(Map<Key, Entry> values) {
        this.entries = new EnumMap<>(Key.class);
        this.entries.putAll(values);
    }

    static Builder builder() {
        return new Builder();
    }

    boolean has(Key key) {
        Entry entry = entries.get(key);
        return entry != null && entry.available;
    }

    boolean has(Key key, int minimumConfidence) {
        Entry entry = entries.get(key);
        return entry != null && entry.available && entry.confidence >= minimumConfidence;
    }

    Entry get(Key key) {
        return entries.get(key);
    }

    List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries.values()) {
            if (out.length() > 0) out.append(" | ");
            out.append(entry.compact());
        }
        return out.toString();
    }

    String humanReport() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries.values()) {
            out.append(entry.available ? "OK   " : "MISS ")
                    .append(entry.key)
                    .append(" source=").append(entry.source)
                    .append(" confidence=").append(entry.confidence);
            if (!entry.detail.isBlank()) out.append(" detail=").append(entry.detail);
            out.append('\n');
        }
        return out.toString();
    }
}
