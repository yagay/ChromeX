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
 * Read-only extension capability probe.
 *
 * <p>Google Desktop Android and vendor Chromium forks deliberately use different Java bridges, so
 * they are classified separately. Native scanning is only corroborating evidence: Trichrome can
 * map Monochrome directly from another APK, where there is no ordinary file path available to this
 * process. Complete official Android bridge groups therefore count as strong FULL evidence.</p>
 */
public final class ExtensionCapabilityDetector {
    private static final int SCAN_BUFFER = 1024 * 1024;

    static final String[] CORE_BROWSER_CLASSES = {
            "org.chromium.chrome.browser.ChromeTabbedActivity",
            "org.chromium.ui.base.WindowAndroid",
            "org.chromium.chrome.browser.profiles.Profile",
            "org.chromium.content_public.browser.WebContents"
    };

    static final String[] GOOGLE_DESKTOP_CLASSES = {
            "org.chromium.chrome.browser.ui.extensions.ExtensionActionsBridge",
            "org.chromium.chrome.browser.ui.extensions.ExtensionsToolbarBridge",
            "org.chromium.chrome.browser.ui.extensions.ExtensionActionPopupContents",
            "org.chromium.chrome.browser.ui.extensions.ExtensionInstallDialogBridge",
            "org.chromium.chrome.browser.ui.extensions.ExtensionDeveloperPrivateBridge",
            "org.chromium.chrome.browser.ui.extensions.ExtensionUtilBridge",
            "org.chromium.chrome.browser.ui.extensions.windowing.ExtensionWindowControllerBridgeImpl",
            "org.chromium.chrome.browser.toolbar.extensions.ExtensionActionListContainer",
            "org.chromium.chrome.browser.extensions.ExtensionsUrlOverrideRegistryManager"
    };

    static final String[] VENDOR_CLASSES = {
            "org.chromium.chrome.browser.extensions.ExtensionSystemManager",
            "org.chromium.chrome.browser.extensions.ExtensionActionBridge",
            "org.chromium.chrome.browser.extensions.ExtensionActionManagerBridge",
            "org.chromium.chrome.browser.extensions.ExtensionInstallerBridge",
            "org.chromium.chrome.browser.extensions.ExtensionDialogUtil",
            "org.chromium.chrome.browser.ui.extensions.ExtensionActionBridgeController"
    };

    static final String[] NATIVE_MARKERS = {
            "ExtensionSystemImpl",
            "ExtensionService",
            "ExtensionRegistry",
            "ExtensionInstaller",
            "ExtensionFunctionDispatcher",
            "ExtensionUserScriptLoader",
            "WebRequestEventRouter",
            "ScriptInjection",
            "ToolbarActionsModel",
            "BrowserExtensionWindowController",
            "chrome-extension://"
    };

    private ExtensionCapabilityDetector() {}

    public static ExtensionCapabilityReport detect(ClassLoader classLoader) {
        List<String> javaHits = new ArrayList<>();
        List<String> javaMisses = new ArrayList<>();
        probeClasses(classLoader, CORE_BROWSER_CLASSES, javaHits, javaMisses);
        probeClasses(classLoader, GOOGLE_DESKTOP_CLASSES, javaHits, javaMisses);
        probeClasses(classLoader, VENDOR_CLASSES, javaHits, javaMisses);

        NativeMapCandidate mapped = findMappedChromiumLibrary();
        List<String> nativeHits = new ArrayList<>();
        List<String> nativeMisses = new ArrayList<>();
        if (mapped != null && mapped.scannablePath != null) {
            try {
                Set<String> found = scanMarkers(new File(mapped.scannablePath), NATIVE_MARKERS);
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

        ExtensionRuntimeMode mode = classify(javaHits, nativeHits);
        String library = mapped == null ? null : mapped.display;
        return new ExtensionCapabilityReport(mode, javaHits, javaMisses,
                nativeHits, nativeMisses, library);
    }

    static ExtensionRuntimeMode classify(List<String> javaHits, List<String> nativeHits) {
        int core = count(javaHits, CORE_BROWSER_CLASSES);
        int google = count(javaHits, GOOGLE_DESKTOP_CLASSES);
        int vendor = count(javaHits, VENDOR_CLASSES);

        int nativeCore = 0;
        for (String marker : new String[]{
                "ExtensionSystemImpl", "ExtensionService", "ExtensionRegistry",
                "ExtensionFunctionDispatcher", "ExtensionUserScriptLoader"}) {
            if (nativeHits != null && nativeHits.contains(marker)) nativeCore++;
        }
        int nativeGoogleUi = 0;
        for (String marker : new String[]{"ToolbarActionsModel", "BrowserExtensionWindowController"}) {
            if (nativeHits != null && nativeHits.contains(marker)) nativeGoogleUi++;
        }

        // Official Desktop Android ships a large, characteristic Android bridge surface. Six of
        // nine official bridge classes is enough even if the shared Trichrome native library is
        // not directly readable. With native corroboration, four bridge hits are sufficient.
        if (google >= 6 || (google >= 4 && nativeCore >= 3) || (google >= 4 && nativeGoogleUi >= 1)) {
            return ExtensionRuntimeMode.GOOGLE_DESKTOP_FULL;
        }

        // Vendor FULL requires its own management/install/action bridge group or strong native
        // core plus at least one vendor-specific Java bridge. This prevents generic native strings
        // in a mobile build from being mistaken for a callable vendor backend.
        if (vendor >= 3 || (vendor >= 1 && nativeCore >= 4)) {
            return ExtensionRuntimeMode.VENDOR_FULL;
        }

        if (core >= 3) return ExtensionRuntimeMode.LITE;
        return ExtensionRuntimeMode.NONE;
    }

    private static void probeClasses(ClassLoader loader, String[] names,
                                     List<String> hits, List<String> misses) {
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
                hits.add(name);
            } catch (Throwable ignored) {
                misses.add(name);
            }
        }
    }

    private static int count(List<String> hits, String[] values) {
        if (hits == null) return 0;
        int count = 0;
        for (String value : values) if (hits.contains(value)) count++;
        return count;
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

    private static NativeMapCandidate findMappedChromiumLibrary() {
        File maps = new File("/proc/self/maps");
        if (!maps.isFile()) return null;
        NativeMapCandidate best = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
            for (String line; (line = reader.readLine()) != null;) {
                int slash = line.indexOf('/');
                if (slash < 0) continue;
                String mapped = line.substring(slash).trim();
                if (mapped.endsWith(" (deleted)")) mapped = mapped.substring(0, mapped.length() - 10);
                if (!looksLikeChromiumNative(mapped)) continue;

                String filePart = mapped;
                int bang = filePart.indexOf("!/");
                if (bang >= 0) filePart = filePart.substring(0, bang);
                File candidate = new File(filePart);
                String scan = bang < 0 && candidate.isFile() ? candidate.getAbsolutePath() : null;
                NativeMapCandidate current = new NativeMapCandidate(mapped, scan);
                if (scan != null) return current;
                if (best == null) best = current;
            }
        } catch (Throwable ignored) {}
        return best;
    }

    private static boolean looksLikeChromiumNative(String path) {
        String lower = path.toLowerCase();
        return lower.contains("libchrome.so")
                || lower.contains("libmonochrome.so")
                || lower.contains("libmonochrome_64.so")
                || lower.contains("trichromelibrary")
                || lower.contains("trichrome_library");
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

    private static final class NativeMapCandidate {
        final String display;
        final String scannablePath;

        NativeMapCandidate(String display, String scannablePath) {
            this.display = display;
            this.scannablePath = scannablePath;
        }
    }
}
