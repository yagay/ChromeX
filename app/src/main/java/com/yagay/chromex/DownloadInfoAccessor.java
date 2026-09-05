package com.yagay.chromex;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Semantic DownloadInfo accessor shared by verified Chrome builds and obfuscated Chromium forks.
 *
 * <p>Stable accessors are preferred. Exact-build fields are only a verified fallback. Unknown
 * builds fall back to {@link AdaptiveDownloadInfo}, which classifies String values by shape instead
 * of R8 field names. Feature code should use this class rather than reading DownloadInfo directly.</p>
 */
final class DownloadInfoAccessor {
    static final class Values {
        final String mime;
        final String path;
        final String name;
        final String detail;

        Values(String mime, String path, String name, String detail) {
            this.mime = blank(mime);
            this.path = blank(path);
            this.name = blank(name);
            this.detail = detail == null ? "" : detail;
        }

        boolean usable() {
            return path != null || name != null;
        }
    }

    private DownloadInfoAccessor() {}

    static Values read(Object info, ChromiumProfile profile) {
        if (info == null) return new Values(null, null, null, "info=null");

        String mime = getter(info, "getMimeType", "getMime", "mimeType");
        String path = getter(info, "getFilePath", "getPath", "getTargetPath", "getDownloadPath");
        String name = getter(info, "getFileName", "getFilename", "getName", "getDisplayName");
        StringBuilder detail = new StringBuilder();
        if (mime != null || path != null || name != null) detail.append("stable-accessors");

        if (profile != null && profile.isVerifiedExact()) {
            if (mime == null) mime = stringField(info, "c");
            if (profile.is152()) {
                if (path == null) path = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);
                if (name == null) name = stringField(info, Chrome152.DOWNLOAD_INFO_NAME);
            } else if (profile.is145()) {
                if (path == null) path = stringField(info, "e");
                if (name == null) name = stringField(info, "g");
            }
            if ((mime != null || path != null || name != null) && detail.length() == 0) {
                detail.append("verified-exact-fields");
            }
        }

        if (mime == null || path == null || name == null) {
            AdaptiveDownloadInfo.Values structural = AdaptiveDownloadInfo.extract(info);
            if (mime == null) mime = structural.mime;
            if (path == null) path = structural.path;
            if (name == null) name = structural.name;
            if (structural.detail != null && !structural.detail.isBlank()) {
                if (detail.length() > 0) detail.append(',');
                detail.append(structural.detail);
            }
        }

        if (name == null && path != null && path.startsWith("/")) {
            try { name = new File(path).getName(); }
            catch (Throwable ignored) {}
        }
        if (detail.length() == 0) detail.append("unresolved");
        return new Values(mime, path, name, detail.toString());
    }

    /**
     * Rewrites only String values that are semantically tied to the old path/name.
     * This avoids hard-coding vendor field names and never touches MIME/GUID/URL strings.
     */
    static boolean rewrite(Object info, ChromiumProfile profile, File target) {
        if (info == null || target == null) return false;
        Values before = read(info, profile);
        if (!before.usable()) return false;
        final String newPath;
        final String newName;
        try {
            File canonical = target.getCanonicalFile();
            newPath = canonical.getAbsolutePath();
            newName = canonical.getName();
        } catch (Throwable t) {
            return false;
        }

        boolean changed = false;
        changed |= setter(info, "setFilePath", newPath);
        changed |= setter(info, "setPath", newPath);
        changed |= setter(info, "setFileName", newName);
        changed |= setter(info, "setFilename", newName);
        changed |= setter(info, "setDisplayName", newName);

        String oldPath = before.path;
        String oldName = before.name;
        String oldBase = null;
        if (oldPath != null && oldPath.startsWith("/")) {
            try { oldBase = new File(oldPath).getName(); }
            catch (Throwable ignored) {}
        }

        Class<?> type = info.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(info);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    String replacement = null;
                    if (oldPath != null && oldPath.equals(value)) replacement = newPath;
                    else if (oldName != null && oldName.equals(value)
                            && AdaptiveDownloadInfo.looksLikeFileName(value)) replacement = newName;
                    else if (oldBase != null && oldBase.equals(value)
                            && AdaptiveDownloadInfo.looksLikeFileName(value)) replacement = newName;
                    if (replacement != null && !replacement.equals(value)) {
                        field.set(info, replacement);
                        changed = true;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return changed;
    }

    static Object find(Object[] args, ClassLoader loader) {
        if (args == null || loader == null) return null;
        try {
            Class<?> type = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getter(Object owner, String... names) {
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

    private static boolean setter(Object owner, String name, String value) {
        if (owner == null || value == null) return false;
        try {
            Method method = Reflect.exact(owner.getClass(), name, String.class);
            if (method.getReturnType() != void.class && method.getReturnType() != owner.getClass()) {
                // Fluent setters in vendor builds are still safe; arbitrary methods are not.
                if (!method.getReturnType().isAssignableFrom(owner.getClass())
                        && !owner.getClass().isAssignableFrom(method.getReturnType())) return false;
            }
            method.invoke(owner, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
