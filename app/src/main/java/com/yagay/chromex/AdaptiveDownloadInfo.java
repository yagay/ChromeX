package com.yagay.chromex;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts download metadata from Chromium forks whose DownloadInfo accessors were removed or
 * obfuscated. Stable getters are preferred; instance String fields are used only as a structural
 * fallback and are classified by value shape rather than by R8 field name.
 */
final class AdaptiveDownloadInfo {
    static final class Values {
        final String mime;
        final String path;
        final String name;
        final String detail;

        Values(String mime, String path, String name, String detail) {
            this.mime = blankToNull(mime);
            this.path = blankToNull(path);
            this.name = blankToNull(name);
            this.detail = detail == null ? "" : detail;
        }

        boolean usable() {
            return path != null || name != null;
        }
    }

    private static final String[] MIME_GETTERS = {"getMimeType", "getMime", "mimeType"};
    private static final String[] PATH_GETTERS = {
            "getFilePath", "getPath", "getTargetPath", "getDownloadPath"
    };
    private static final String[] NAME_GETTERS = {
            "getFileName", "getFilename", "getName", "getDisplayName"
    };

    private AdaptiveDownloadInfo() {}

    static Values extract(Object info) {
        if (info == null) return new Values(null, null, null, "info=null");

        String mime = invokeString(info, MIME_GETTERS);
        String path = invokeString(info, PATH_GETTERS);
        String name = invokeString(info, NAME_GETTERS);
        StringBuilder detail = new StringBuilder();

        List<FieldValue> strings = stringFields(info);
        if (mime == null) {
            FieldValue selected = best(strings, AdaptiveDownloadInfo::mimeScore);
            if (selected != null && mimeScore(selected.value) > 0) {
                mime = selected.value;
                append(detail, "mime<-" + selected.fieldName);
            }
        }
        if (path == null) {
            FieldValue selected = best(strings, AdaptiveDownloadInfo::pathScore);
            if (selected != null && pathScore(selected.value) > 0) {
                path = selected.value;
                append(detail, "path<-" + selected.fieldName);
            }
        }
        if (name == null) {
            FieldValue selected = best(strings, AdaptiveDownloadInfo::nameScore);
            if (selected != null && nameScore(selected.value) > 0) {
                name = selected.value;
                append(detail, "name<-" + selected.fieldName);
            }
        }

        if (name == null && path != null && !path.startsWith("content://")) {
            String clean = path.startsWith("file://") ? path.substring("file://".length()) : path;
            try {
                String derived = new File(clean).getName();
                if (looksLikeFileName(derived)) {
                    name = derived;
                    append(detail, "name<-basename");
                }
            } catch (Throwable ignored) {}
        }

        if (detail.length() == 0) detail.append("stable-accessors");
        return new Values(mime, path, name, detail.toString());
    }

    static boolean looksLikeMime(String value) {
        if (value == null) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        int slash = text.indexOf('/');
        if (slash <= 0 || slash + 1 >= text.length() || text.indexOf("//") >= 0) return false;
        if (text.contains(" ") || text.contains("\\") || text.startsWith("http")) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '+' || c == '.'
                    || c == '_' || c == ';' || c == '=') continue;
            return false;
        }
        return true;
    }

    static boolean looksLikePath(String value) {
        if (value == null || value.isBlank()) return false;
        String text = value.trim();
        if (text.startsWith("content://") || text.startsWith("file://")) return true;
        if (text.startsWith("/") && text.length() > 1) return true;
        return text.indexOf('\\') > 1 && !text.contains("://");
    }

    static boolean looksLikeFileName(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.isEmpty() || text.length() > 255 || text.contains("://")
                || text.contains("/") || text.contains("\\") || looksLikeMime(text)) return false;
        if (looksLikeGuid(text)) return false;
        int dot = text.lastIndexOf('.');
        return dot > 0 && dot + 1 < text.length() && text.length() - dot <= 16;
    }

    private static int mimeScore(String value) {
        if (!looksLikeMime(value)) return -1;
        String low = value.toLowerCase(Locale.ROOT);
        int score = 50;
        if (low.startsWith("application/") || low.startsWith("image/")
                || low.startsWith("video/") || low.startsWith("audio/")
                || low.startsWith("text/")) score += 20;
        if (low.contains("octet-stream")) score -= 5;
        return score;
    }

    private static int pathScore(String value) {
        if (!looksLikePath(value)) return -1;
        String text = value.trim();
        int score = 40;
        if (text.startsWith("content://")) score += 35;
        if (text.startsWith("/storage/") || text.startsWith("/sdcard/")) score += 30;
        if (text.toLowerCase(Locale.ROOT).contains("/download")) score += 20;
        if (looksLikeFileName(lastSegment(text))) score += 15;
        return score;
    }

    private static int nameScore(String value) {
        if (!looksLikeFileName(value)) return -1;
        int score = 40;
        String low = value.toLowerCase(Locale.ROOT);
        if (low.endsWith(".apk") || low.endsWith(".pdf") || low.endsWith(".zip")
                || low.endsWith(".txt") || low.endsWith(".jpg") || low.endsWith(".png")
                || low.endsWith(".mp4") || low.endsWith(".mp3")) score += 10;
        return score;
    }

    private static List<FieldValue> stringFields(Object owner) {
        ArrayList<FieldValue> result = new ArrayList<>();
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(owner);
                    if (raw instanceof String && !((String) raw).isBlank()) {
                        result.add(new FieldValue(type.getSimpleName() + "." + field.getName(),
                                (String) raw));
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return result;
    }

    private static String invokeString(Object owner, String[] names) {
        for (String name : names) {
            try {
                Method method = Reflect.exact(owner.getClass(), name);
                if (method.getParameterCount() != 0 || method.getReturnType() != String.class) continue;
                Object value = method.invoke(owner);
                if (value instanceof String && !((String) value).isBlank()) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private interface Scorer { int score(String value); }

    private static FieldValue best(List<FieldValue> values, Scorer scorer) {
        FieldValue best = null;
        int bestScore = Integer.MIN_VALUE;
        for (FieldValue value : values) {
            int score = scorer.score(value.value);
            if (score > bestScore) {
                best = value;
                bestScore = score;
            }
        }
        return best;
    }

    private static String lastSegment(String value) {
        if (value == null) return "";
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static boolean looksLikeGuid(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() == 36 && v.charAt(8) == '-' && v.charAt(13) == '-'
                && v.charAt(18) == '-' && v.charAt(23) == '-') return true;
        return v.length() >= 24 && v.matches("[0-9a-fA-F_-]+");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void append(StringBuilder out, String value) {
        if (out.length() > 0) out.append(',');
        out.append(value);
    }

    private static final class FieldValue {
        final String fieldName;
        final String value;

        FieldValue(String fieldName, String value) {
            this.fieldName = fieldName;
            this.value = value;
        }
    }
}
