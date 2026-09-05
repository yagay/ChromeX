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
}
