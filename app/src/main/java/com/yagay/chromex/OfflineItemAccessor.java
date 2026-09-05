package com.yagay.chromex;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Value-shape accessor for R8-obfuscated OfflineItem objects. */
final class OfflineItemAccessor {
    private static final String CONTENT_ID =
            "org.chromium.components.offline_items_collection.ContentId";

    static final class Values {
        final String path;
        final String name;
        final String mime;
        final Object contentId;
        final String contentKey;
        final String detail;

        Values(String path, String name, String mime, Object contentId,
               String contentKey, String detail) {
            this.path = emptyToNull(path);
            this.name = emptyToNull(name);
            this.mime = emptyToNull(mime);
            this.contentId = contentId;
            this.contentKey = emptyToNull(contentKey);
            this.detail = detail == null ? "" : detail;
        }

        boolean usable() {
            return path != null || name != null || contentId != null;
        }
    }

    private OfflineItemAccessor() {}

    static Values read(Object item) {
        if (item == null) return new Values(null, null, null, null, null, "item=null");

        String path = stringField(item, "filePath");
        String name = stringField(item, "title");
        String mime = stringField(item, "mimeType");
        Object contentId = objectField(item, "id");
        String detail = "stable";

        List<Field> fields = instanceFields(item.getClass());
        if (path == null || mime == null || name == null || contentId == null) {
            detail = "shape";
            for (Field field : fields) {
                Object value = get(field, item);
                if (value == null) continue;
                if (contentId == null && CONTENT_ID.equals(value.getClass().getName())) {
                    contentId = value;
                    continue;
                }
                if (!(value instanceof String)) continue;
                String text = (String) value;
                if (path == null && AdaptiveDownloadInfo.looksLikePath(text)) {
                    path = text;
                    continue;
                }
                if (mime == null && AdaptiveDownloadInfo.looksLikeMime(text)) {
                    mime = text;
                }
            }
            for (Field field : fields) {
                Object value = get(field, item);
                if (!(value instanceof String)) continue;
                String text = (String) value;
                if (name == null
                        && !text.equals(path)
                        && !text.equals(mime)
                        && AdaptiveDownloadInfo.looksLikeFileName(text)) {
                    name = text;
                    break;
                }
            }
        }

        if (name == null && path != null) {
            try { name = new java.io.File(path).getName(); } catch (Throwable ignored) {}
        }
        String key = contentKey(contentId);
        return new Values(path, name, mime, contentId, key, detail);
    }

    static Object contentId(Object item) {
        return read(item).contentId;
    }

    static String contentKey(Object contentId) {
        if (contentId == null) return null;
        String namespace = stringField(contentId, "namespace");
        String id = stringField(contentId, "id");
        if (namespace == null || id == null) {
            ArrayList<String> values = new ArrayList<>();
            for (Field field : instanceFields(contentId.getClass())) {
                Object value = get(field, contentId);
                if (value instanceof String && !((String) value).isBlank()) {
                    values.add((String) value);
                }
            }
            if (values.size() >= 2) {
                namespace = values.get(0);
                id = values.get(1);
            }
        }
        if (namespace == null && id == null) return null;
        return String.valueOf(namespace) + ':' + String.valueOf(id);
    }

    static List<Field> instanceFields(Class<?> start) {
        ArrayList<Field> out = new ArrayList<>();
        Class<?> type = start;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try { field.setAccessible(true); } catch (Throwable ignored) {}
                out.add(field);
            }
            type = type.getSuperclass();
        }
        return out;
    }

    private static String stringField(Object owner, String name) {
        Object value = objectField(owner, name);
        return value instanceof String ? (String) value : null;
    }

    private static Object objectField(Object owner, String name) {
        if (owner == null || name == null) return null;
        try { return Reflect.get(owner, name); }
        catch (Throwable ignored) { return null; }
    }

    static Object get(Field field, Object owner) {
        try { return field.get(owner); }
        catch (Throwable ignored) { return null; }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
