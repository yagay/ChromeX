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

/** Fallback Chromium product-version scanner for vendor APK/split layouts. */
final class ChromiumEngineVersionScanner {
    private static final Pattern VERSION = Pattern.compile("\\d{2,3}\\.\\d+\\.\\d+\\.\\d+");

    private ChromiumEngineVersionScanner() {}

    static String scan(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null || runtime.chromeSplitPath == null) return null;
        File file = new File(runtime.chromeSplitPath);
        if (!file.isFile()) return null;

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
        } catch (Throwable zipFailure) {
            // Some test/custom runtimes can point directly at a dex file rather than an APK.
            try (FileInputStream in = new FileInputStream(file)) {
                best = bestInBytes(IoCompat.readFully(in));
            } catch (Throwable ignored) {
                if (hooks != null) hooks.warn("adaptive engine dex scan failed: "
                        + zipFailure.getClass().getSimpleName());
            }
        }

        if (best != null && hooks != null) {
            hooks.info("adaptive resolver: Chromium engine=" + best
                    + " via split APK dex literal");
        }
        return best;
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
            // Filters loopback literals such as 127.0.0.1 while retaining Chromium-style builds.
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
