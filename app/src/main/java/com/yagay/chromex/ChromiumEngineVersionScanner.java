package com.yagay.chromex;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fallback Chromium product-version scanner for single APK and arbitrary split layouts. */
final class ChromiumEngineVersionScanner {
    private static final Pattern VERSION = Pattern.compile("\\d{2,3}\\.\\d+\\.\\d+\\.\\d+");

    private ChromiumEngineVersionScanner() {}

    static String scan(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || runtime.dexPaths().isEmpty()) return null;
        String best = null;
        String bestSource = null;
        Throwable last = null;
        for (String path : runtime.dexPaths()) {
            if (path == null || path.isBlank()) continue;
            File file = new File(path);
            if (!file.isFile()) continue;
            try {
                String candidate = scanFile(file);
                if (candidate != null && (best == null || compare(candidate, best) > 0)) {
                    best = candidate;
                    bestSource = file.getName();
                }
            } catch (Throwable t) {
                last = t;
            }
        }

        if (best != null && hooks != null) {
            hooks.info("adaptive resolver: Chromium engine=" + best
                    + " via dex literal source=" + bestSource);
        } else if (best == null && last != null && hooks != null) {
            hooks.warn("adaptive engine dex scan failed: " + last.getClass().getSimpleName());
        }
        return best;
    }

    private static String scanFile(File file) throws Exception {
        String best = null;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name == null
                        || !name.startsWith("classes") || !name.endsWith(".dex")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    String candidate = bestInBytes(IoCompat.readFully(in));
                    if (candidate != null && (best == null || compare(candidate, best) > 0)) {
                        best = candidate;
                    }
                }
            }
            return best;
        } catch (java.util.zip.ZipException notZip) {
            // Custom/test runtimes may point directly at a dex file.
            try (FileInputStream in = new FileInputStream(file)) {
                return bestInBytes(IoCompat.readFully(in));
            }
        }
    }

    static String bestInBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher matcher = VERSION.matcher(text);
        String best = null;
        while (matcher.find()) {
            String value = matcher.group();
            if (!plausible(value)) continue;
            if (best == null || compare(value, best) > 0) best = value;
        }
        return best;
    }

    static boolean plausible(String value) {
        if (value == null || !VERSION.matcher(value).matches()) return false;
        String[] p = value.split("\\.");
        if (p.length != 4) return false;
        try {
            int major = Integer.parseInt(p[0]);
            int build = Integer.parseInt(p[2]);
            return major >= 60 && major <= 250 && build >= 1000;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            try {
                int av = Integer.parseInt(a[i]);
                int bv = Integer.parseInt(b[i]);
                if (av != bv) return Integer.compare(av, bv);
            } catch (Throwable ignored) {}
        }
        return Integer.compare(a.length, b.length);
    }
}
