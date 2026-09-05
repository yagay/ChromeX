package com.yagay.chromex;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Final UI-only cleanup for Chromium builds whose DownloadItem -> OfflineItem materializer keeps
 * a stale uniquified display name even after the completed file has been normalized back to the
 * original basename. This never renames files; it only reconciles string metadata returned to the
 * downloads UI.
 */
final class OfflineItemDisplayNameNormalizer {
    private final ChromeRuntime runtime;
    private final HookSupport hooks;

    OfflineItemDisplayNameNormalizer(ChromeRuntime runtime, HookSupport hooks) {
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void install() {
        Method materializer = DownloadOfflineItemBinding.resolve(runtime.classLoader);
        if (materializer == null) {
            hooks.warn("offline display-name normalizer: materializer unresolved");
            return;
        }
        hooks.method(materializer, "chromex:offline-display-name-normalizer", chain -> {
            Object result = chain.proceed();
            if (result != null) normalize(result);
            return result;
        });
        hooks.info("offline display-name normalizer installed: "
                + materializer.getDeclaringClass().getName() + '#' + materializer.getName());
    }

    private void normalize(Object offlineItem) {
        try {
            List<FieldValue> strings = stringFields(offlineItem);
            if (strings.isEmpty()) return;

            List<File> candidatePaths = new ArrayList<>();
            for (FieldValue value : strings) {
                File file = absoluteFile(value.value);
                if (file != null) candidatePaths.add(file);
            }

            int changed = 0;
            for (FieldValue fieldValue : strings) {
                String oldValue = fieldValue.value;
                String numberedName = fileNameCandidate(oldValue);
                if (numberedName == null) continue;
                String originalName = DownloadNamePolicy.originalNameFromUniquified(numberedName);
                if (originalName == null) continue;

                File target = resolveExistingOriginal(candidatePaths, numberedName, originalName);
                if (target == null) continue;

                String replacement = replaceTail(oldValue, numberedName, originalName);
                if (replacement == null || replacement.equals(oldValue)) continue;
                try {
                    fieldValue.field.setAccessible(true);
                    fieldValue.field.set(offlineItem, replacement);
                    changed++;
                } catch (Throwable ignored) {}
            }
            if (changed > 0) {
                hooks.info("offline download display name normalized fields=" + changed);
            }
        } catch (Throwable t) {
            hooks.warn("offline display-name normalization failed: "
                    + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static File resolveExistingOriginal(List<File> paths, String numberedName,
                                                String originalName) {
        for (File raw : paths) {
            try {
                File file = raw.getCanonicalFile();
                File parent = file.getParentFile();
                if (parent == null) continue;

                // The path may already have been rewritten to the original name, or may still be
                // the stale numbered path. In both cases only rewrite display metadata when the
                // normalized target really exists in the same directory.
                File target = new File(parent, originalName).getCanonicalFile();
                if (!target.isFile()) continue;
                if (!target.getParentFile().equals(parent.getCanonicalFile())) continue;

                String fileName = file.getName();
                if (originalName.equals(fileName) || numberedName.equals(fileName)
                        || !file.exists()) {
                    return target;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<FieldValue> stringFields(Object owner) {
        ArrayList<FieldValue> out = new ArrayList<>();
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof String && !((String) value).isBlank()) {
                        out.add(new FieldValue(field, (String) value));
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return out;
    }

    private static File absoluteFile(String value) {
        if (value == null || value.isBlank()) return null;
        String raw = value;
        if (raw.startsWith("file://")) raw = raw.substring("file://".length());
        if (!raw.startsWith("/")) return null;
        try { return new File(raw).getCanonicalFile(); }
        catch (Throwable ignored) { return null; }
    }

    private static String fileNameCandidate(String value) {
        if (value == null || value.isBlank()) return null;
        String raw = value;
        if (raw.startsWith("file://")) raw = raw.substring("file://".length());
        int slash = raw.lastIndexOf('/');
        return slash >= 0 ? raw.substring(slash + 1) : raw;
    }

    private static String replaceTail(String value, String oldName, String newName) {
        if (value == null || oldName == null || newName == null) return null;
        if (value.equals(oldName)) return newName;
        if (value.endsWith('/' + oldName)) {
            return value.substring(0, value.length() - oldName.length()) + newName;
        }
        return null;
    }

    private static final class FieldValue {
        final Field field;
        final String value;

        FieldValue(Field field, String value) {
            this.field = field;
            this.value = value;
        }
    }
}
