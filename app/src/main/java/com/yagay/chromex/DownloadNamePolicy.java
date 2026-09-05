package com.yagay.chromex;

import java.io.File;

final class DownloadNamePolicy {
    private DownloadNamePolicy() {}

    static String fileNameOnly(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.startsWith("content://")) {
                android.net.Uri uri = android.net.Uri.parse(value);
                String segment = uri.getLastPathSegment();
                return segment == null || segment.isBlank() ? null : segment;
            }
            return new File(value).getName();
        } catch (Throwable ignored) {
            return value;
        }
    }

    static boolean matchesUniquifiedName(String wanted, String got) {
        if (wanted == null || got == null) return false;
        if (wanted.equals(got)) return true;

        int dot = wanted.lastIndexOf('.');
        String stem = dot > 0 ? wanted.substring(0, dot) : wanted;
        String ext = dot > 0 ? wanted.substring(dot) : "";
        String prefix = stem + " (";
        String suffix = ")" + ext;
        if (!got.startsWith(prefix) || !got.endsWith(suffix)) return false;
        int numberEnd = got.length() - suffix.length();
        if (numberEnd <= prefix.length()) return false;
        String number = got.substring(prefix.length(), numberEnd);
        if (number.isEmpty()) return false;
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Returns the original basename for Chromium's "name (n).ext" conflict format.
     * Returns null when the supplied name is not an exact Chromium-style uniquified name.
     */
    static String originalNameFromUniquified(String got) {
        if (got == null || got.isBlank()) return null;

        int dot = got.lastIndexOf('.');
        String ext = dot > 0 ? got.substring(dot) : "";
        String stem = dot > 0 ? got.substring(0, dot) : got;
        if (!stem.endsWith(")")) return null;

        int open = stem.lastIndexOf(" (");
        if (open <= 0 || open + 2 >= stem.length() - 1) return null;
        String number = stem.substring(open + 2, stem.length() - 1);
        if (number.isEmpty()) return null;
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) return null;
        }

        String original = stem.substring(0, open) + ext;
        return original.isBlank() ? null : original;
    }
}
