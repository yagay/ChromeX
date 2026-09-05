package com.yagay.chromex;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects whether the target Chromium build contains a usable native extension runtime.
 * This probe is deliberately read-only: it never hooks or invokes extension internals.
 * Native scanning is streaming so a ~200 MiB libchrome.so is never copied into the Java heap.
 */
public final class ExtensionCapabilityDetector {
    private static final int SCAN_BUFFER = 1024 * 1024;

    private static final String[] JAVA_CLASSES = {
            "org.chromium.chrome.browser.ChromeTabbedActivity",
            "org.chromium.ui.base.WindowAndroid",
            "org.chromium.chrome.browser.profiles.Profile",
            "org.chromium.content_public.browser.WebContents",
            "org.chromium.chrome.browser.extensions.ExtensionSystemManager",
            "org.chromium.chrome.browser.extensions.ExtensionActionBridge",
            "org.chromium.chrome.browser.extensions.ExtensionActionManagerBridge",
            "org.chromium.chrome.browser.extensions.ExtensionInstallerBridge"
    };

    private static final String[] NATIVE_MARKERS = {
            "ExtensionSystemImpl",
            "ExtensionService",
            "ExtensionRegistry",
            "ExtensionInstaller",
            "ExtensionFunctionDispatcher",
            "ExtensionUserScriptLoader",
            "WebRequestEventRouter",
            "ScriptInjection",
            "chrome-extension://"
    };

    private ExtensionCapabilityDetector() {}

    public static ExtensionCapabilityReport detect(ClassLoader classLoader) {
        List<String> javaHits = new ArrayList<>();
        List<String> javaMisses = new ArrayList<>();
        for (String name : JAVA_CLASSES) {
            try {
                Class.forName(name, false, classLoader);
                javaHits.add(name);
            } catch (Throwable ignored) {
                javaMisses.add(name);
            }
        }

        String lib = findMappedChromeLibrary();
        List<String> nativeHits = new ArrayList<>();
        List<String> nativeMisses = new ArrayList<>();
        if (lib != null) {
            try {
                Set<String> found = scanMarkers(new File(lib), NATIVE_MARKERS);
                for (String marker : NATIVE_MARKERS) {
                    if (found.contains(marker)) nativeHits.add(marker);
                    else nativeMisses.add(marker);
                }
            } catch (Throwable ignored) {
                nativeMisses.addAll(Arrays.asList(NATIVE_MARKERS));
            }
        } else {
            nativeMisses.addAll(Arrays.asList(NATIVE_MARKERS));
        }

        boolean fullJavaBridge = containsClass(javaHits,
                "org.chromium.chrome.browser.extensions.ExtensionSystemManager");
        int strongNative = 0;
        for (String marker : new String[]{
                "ExtensionSystemImpl", "ExtensionService", "ExtensionRegistry",
                "ExtensionFunctionDispatcher", "ExtensionUserScriptLoader"}) {
            if (nativeHits.contains(marker)) strongNative++;
        }

        ExtensionRuntimeMode mode;
        if (strongNative >= 4) mode = ExtensionRuntimeMode.FULL;
        else if (hasCoreBrowserAnchors(javaHits)) mode = ExtensionRuntimeMode.LITE;
        else mode = ExtensionRuntimeMode.NONE;

        if (fullJavaBridge && strongNative >= 3) mode = ExtensionRuntimeMode.FULL;

        return new ExtensionCapabilityReport(mode, javaHits, javaMisses,
                nativeHits, nativeMisses, lib);
    }

    static Set<String> scanMarkers(File file, String[] markers) throws Exception {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        if (file == null || !file.isFile() || markers == null || markers.length == 0) return found;

        byte[][] needles = new byte[markers.length][];
        int maxNeedle = 1;
        for (int i = 0; i < markers.length; i++) {
            needles[i] = markers[i].getBytes(StandardCharsets.UTF_8);
            maxNeedle = Math.max(maxNeedle, needles[i].length);
        }
        int carryLimit = Math.max(0, maxNeedle - 1);
        byte[] read = new byte[SCAN_BUFFER];
        byte[] window = new byte[SCAN_BUFFER + carryLimit];
        int carry = 0;

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), SCAN_BUFFER)) {
            for (int n; (n = in.read(read)) >= 0;) {
                if (n == 0) continue;
                System.arraycopy(read, 0, window, carry, n);
                int length = carry + n;
                for (int i = 0; i < markers.length; i++) {
                    if (found.contains(markers[i])) continue;
                    if (contains(window, length, needles[i])) found.add(markers[i]);
                }
                if (found.size() == markers.length) break;
                carry = Math.min(carryLimit, length);
                if (carry > 0) System.arraycopy(window, length - carry, window, 0, carry);
            }
        }
        return found;
    }

    private static boolean hasCoreBrowserAnchors(List<String> hits) {
        return containsClass(hits, "org.chromium.chrome.browser.ChromeTabbedActivity")
                && containsClass(hits, "org.chromium.ui.base.WindowAndroid")
                && containsClass(hits, "org.chromium.content_public.browser.WebContents");
    }

    private static boolean containsClass(List<String> hits, String name) {
        return hits.contains(name);
    }

    private static String findMappedChromeLibrary() {
        File maps = new File("/proc/self/maps");
        if (!maps.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
            for (String line; (line = reader.readLine()) != null;) {
                int slash = line.indexOf('/');
                if (slash < 0) continue;
                String path = line.substring(slash).trim();
                if (path.endsWith(" (deleted)")) path = path.substring(0, path.length() - 10);
                File candidate = new File(path);
                if (path.endsWith("/libchrome.so") && candidate.isFile()) return candidate.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean contains(byte[] data, int dataLength, byte[] needle) {
        if (needle.length == 0 || dataLength < needle.length) return false;
        outer:
        for (int i = 0; i <= dataLength - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
