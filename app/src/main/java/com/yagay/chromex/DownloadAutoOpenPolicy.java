package com.yagay.chromex;

import android.content.SharedPreferences;
import android.webkit.MimeTypeMap;

import java.util.Locale;

/** Classifies completed downloads and decides whether the selected file type should auto-open. */
final class DownloadAutoOpenPolicy {
    static final class Match {
        final String configKey;
        final String mime;
        final String category;

        Match(String configKey, String mime, String category) {
            this.configKey = configKey;
            this.mime = mime;
            this.category = category;
        }
    }

    private DownloadAutoOpenPolicy() {}

    static Match match(SharedPreferences prefs, String rawMime, String nameOrPath) {
        String mime = normalizeMime(rawMime);
        String ext = extension(nameOrPath);
        String key = classifyKey(mime, ext);
        if (key == null || !Config.get(prefs, key)) return null;
        return new Match(key, effectiveMime(mime, ext), categoryName(key));
    }

    static boolean isApk(String rawMime, String nameOrPath) {
        String mime = normalizeMime(rawMime);
        return Config.AUTO_OPEN_APK.equals(classifyKey(mime, extension(nameOrPath)));
    }

    private static String classifyKey(String mime, String ext) {
        // Explicit extension wins over MIME because Chromium/servers frequently report generic or
        // even incorrect MIME types for downloaded files. In particular, bundle formats must not
        // be sent to the platform APK installer just because a server labels them package-archive.
        if (is(ext, "apk")) return Config.AUTO_OPEN_APK;
        if (isAny(ext, "apks", "apkm", "xapk")) return Config.AUTO_OPEN_APP_BUNDLE;
        if (is(ext, "pdf")) return Config.AUTO_OPEN_PDF;
        if (isAny(ext, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "cab")) {
            return Config.AUTO_OPEN_ARCHIVE;
        }
        if (isAny(ext, "doc", "docx", "odt", "rtf")) return Config.AUTO_OPEN_DOCUMENT;
        if (isAny(ext, "xls", "xlsx", "ods", "csv")) return Config.AUTO_OPEN_SPREADSHEET;
        if (isAny(ext, "ppt", "pptx", "odp")) return Config.AUTO_OPEN_PRESENTATION;
        if (isAny(ext, "epub", "mobi", "azw", "azw3", "fb2")) return Config.AUTO_OPEN_EBOOK;
        if (isAny(ext, "txt", "md", "markdown", "json", "xml", "yaml", "yml", "log", "ini", "conf", "cfg")) {
            return Config.AUTO_OPEN_TEXT;
        }
        if (isAny(ext, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")) {
            return Config.AUTO_OPEN_IMAGE;
        }
        if (isAny(ext, "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "flv")) {
            return Config.AUTO_OPEN_VIDEO;
        }
        if (isAny(ext, "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr")) {
            return Config.AUTO_OPEN_AUDIO;
        }

        // MIME becomes the fallback when no usable extension was available.
        if (contains(mime, "package-archive")) return Config.AUTO_OPEN_APK;
        if ("application/pdf".equals(mime)) return Config.AUTO_OPEN_PDF;
        if (mime.startsWith("image/")) return Config.AUTO_OPEN_IMAGE;
        if (mime.startsWith("video/")) return Config.AUTO_OPEN_VIDEO;
        if (mime.startsWith("audio/")) return Config.AUTO_OPEN_AUDIO;
        if (mime.startsWith("text/") || "application/json".equals(mime)
                || "application/xml".equals(mime) || "application/xhtml+xml".equals(mime)) {
            return Config.AUTO_OPEN_TEXT;
        }
        if (isOfficeMime(mime, "wordprocessingml", "msword", "opendocument.text")) {
            return Config.AUTO_OPEN_DOCUMENT;
        }
        if (isOfficeMime(mime, "spreadsheetml", "ms-excel", "opendocument.spreadsheet")) {
            return Config.AUTO_OPEN_SPREADSHEET;
        }
        if (isOfficeMime(mime, "presentationml", "ms-powerpoint", "opendocument.presentation")) {
            return Config.AUTO_OPEN_PRESENTATION;
        }
        if ("application/epub+zip".equals(mime)) return Config.AUTO_OPEN_EBOOK;
        if (isArchiveMime(mime)) return Config.AUTO_OPEN_ARCHIVE;
        return null;
    }

    private static String effectiveMime(String mime, String ext) {
        if (is(ext, "apk")) return "application/vnd.android.package-archive";
        if (isAny(ext, "apks", "apkm", "xapk")) return "application/zip";
        if (is(ext, "pdf")) return "application/pdf";
        if (is(ext, "epub")) return "application/epub+zip";
        if (is(ext, "rar")) return "application/vnd.rar";
        if (is(ext, "7z")) return "application/x-7z-compressed";
        if (mime != null && !mime.isBlank() && !"application/octet-stream".equals(mime)
                && !"binary/octet-stream".equals(mime)) {
            return mime;
        }
        if (ext != null && !ext.isBlank()) {
            try {
                String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mapped != null && !mapped.isBlank()) return mapped;
            } catch (Throwable ignored) {}
        }
        String key = classifyKey(mime, ext);
        if (Config.AUTO_OPEN_IMAGE.equals(key)) return "image/*";
        if (Config.AUTO_OPEN_VIDEO.equals(key)) return "video/*";
        if (Config.AUTO_OPEN_AUDIO.equals(key)) return "audio/*";
        if (Config.AUTO_OPEN_TEXT.equals(key)) return "text/plain";
        if (Config.AUTO_OPEN_ARCHIVE.equals(key)) return "application/zip";
        return "application/octet-stream";
    }

    private static String categoryName(String key) {
        if (Config.AUTO_OPEN_APK.equals(key)) return "APK";
        if (Config.AUTO_OPEN_APP_BUNDLE.equals(key)) return "应用安装包";
        if (Config.AUTO_OPEN_PDF.equals(key)) return "PDF";
        if (Config.AUTO_OPEN_ARCHIVE.equals(key)) return "压缩包";
        if (Config.AUTO_OPEN_DOCUMENT.equals(key)) return "文档";
        if (Config.AUTO_OPEN_SPREADSHEET.equals(key)) return "表格";
        if (Config.AUTO_OPEN_PRESENTATION.equals(key)) return "演示文稿";
        if (Config.AUTO_OPEN_TEXT.equals(key)) return "文本";
        if (Config.AUTO_OPEN_IMAGE.equals(key)) return "图片";
        if (Config.AUTO_OPEN_VIDEO.equals(key)) return "视频";
        if (Config.AUTO_OPEN_AUDIO.equals(key)) return "音频";
        if (Config.AUTO_OPEN_EBOOK.equals(key)) return "电子书";
        return "文件";
    }

    private static boolean isArchiveMime(String mime) {
        return contains(mime, "zip") || contains(mime, "rar") || contains(mime, "7z")
                || contains(mime, "tar") || contains(mime, "gzip") || contains(mime, "bzip")
                || contains(mime, "xz") || contains(mime, "zstd") || contains(mime, "cab");
    }

    private static boolean isOfficeMime(String mime, String... needles) {
        if (mime == null || mime.isBlank()) return false;
        for (String value : needles) if (mime.contains(value)) return true;
        return false;
    }

    private static String normalizeMime(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        return separator >= 0 ? normalized.substring(0, separator).trim() : normalized;
    }

    private static String extension(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        int dot = clean.lastIndexOf('.');
        if (dot <= slash || dot + 1 >= clean.length()) return "";
        return clean.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean is(String value, String expected) {
        return expected.equals(value);
    }

    private static boolean isAny(String value, String... expected) {
        for (String candidate : expected) if (candidate.equals(value)) return true;
        return false;
    }

    private static boolean contains(String value, String part) {
        return value != null && value.contains(part);
    }
}
