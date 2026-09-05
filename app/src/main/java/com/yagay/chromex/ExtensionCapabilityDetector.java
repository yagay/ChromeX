package com.yagay.chromex;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Detects whether the target Chromium build contains a usable native extension runtime.
 * This probe is deliberately read-only: it never hooks or invokes extension internals.
 */
public final class ExtensionCapabilityDetector {
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
                byte[] data = readFile(new File(lib));
                for (String marker : NATIVE_MARKERS) {
                    if (contains(data, marker.getBytes(StandardCharsets.UTF_8))) nativeHits.add(marker);
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

        // A Java bridge is a strong positive signal, but FULL still requires native core evidence.
        if (fullJavaBridge && strongNative >= 3) mode = ExtensionRuntimeMode.FULL;

        return new ExtensionCapabilityReport(mode, javaHits, javaMisses,
                nativeHits, nativeMisses, lib);
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
        try {
            String text = new String(readFile(maps), StandardCharsets.UTF_8);
            for (String line : text.split("\\n")) {
                int slash = line.indexOf('/');
                if (slash < 0) continue;
                String path = line.substring(slash).trim();
                if (path.endsWith("/libchrome.so") && new File(path).isFile()) return path;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] readFile(File file) throws IOException {
        long length = file.length();
        if (length > Integer.MAX_VALUE) throw new IOException("file too large");
        byte[] out = new byte[(int) length];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int pos = 0;
            while (pos < out.length) {
                int n = in.read(out, pos, out.length - pos);
                if (n < 0) break;
                pos += n;
            }
            if (pos == out.length) return out;
            return Arrays.copyOf(out, pos);
        }
    }

    private static boolean contains(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) return false;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
