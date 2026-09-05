package com.yagay.chromex;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers installed browsers and marks Chromium-family targets as recommended candidates. */
final class ChromiumTargets {
    static final class Target {
        final String packageName;
        final String label;
        final String family;
        final boolean known;
        final boolean recommended;

        Target(String packageName, String label, String family,
               boolean known, boolean recommended) {
            this.packageName = packageName;
            this.label = label;
            this.family = family;
            this.known = known;
            this.recommended = recommended;
        }

        String displayLabel() {
            return label + "\n" + packageName + " · " + family;
        }
    }

    private static final Map<String, String> KNOWN;
    static {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("com.android.chrome", "Chrome Stable");
        map.put("com.chrome.beta", "Chrome Beta");
        map.put("com.chrome.dev", "Chrome Dev");
        map.put("com.chrome.canary", "Chrome Canary");
        map.put("org.chromium.chrome", "Chromium");
        map.put("org.cromite.cromite", "Cromite");
        map.put("com.kiwibrowser.browser", "Kiwi");
        map.put("com.brave.browser", "Brave");
        map.put("com.brave.browser_beta", "Brave Beta");
        map.put("com.brave.browser_nightly", "Brave Nightly");
        map.put("com.microsoft.emmx", "Edge");
        map.put("com.microsoft.emmx.beta", "Edge Beta");
        map.put("com.microsoft.emmx.dev", "Edge Dev");
        map.put("com.microsoft.emmx.canary", "Edge Canary");
        map.put("com.vivaldi.browser", "Vivaldi");
        map.put("com.vivaldi.browser.snapshot", "Vivaldi Snapshot");
        KNOWN = Collections.unmodifiableMap(map);
    }

    private ChromiumTargets() {}

    static boolean isKnownPackage(String packageName) {
        return packageName != null && KNOWN.containsKey(packageName);
    }

    static boolean isAllowedTarget(String packageName, android.content.SharedPreferences prefs) {
        return isKnownPackage(packageName) || Config.isDynamicTarget(prefs, packageName);
    }

    /**
     * Returns every visible installed HTTPS browser. Chromium-like targets are marked recommended;
     * all remaining browsers are still exposed so the user can explicitly opt them into scope.
     */
    static List<Target> discover(Context context) {
        if (context == null) return Collections.emptyList();
        PackageManager pm = context.getPackageManager();
        Intent intent = browserIntent();

        List<ResolveInfo> handlers;
        try {
            handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        } catch (Throwable t) {
            handlers = Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Target> result = new ArrayList<>();
        for (ResolveInfo info : handlers) {
            if (info == null || info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.equals(context.getPackageName())
                    || !seen.add(packageName)) continue;
            String label = loadLabel(pm, packageName, info);
            result.add(describe(packageName, label));
        }

        // Known Chromium targets can be absent from the resolution result when disabled/default
        // filters differ on an OEM build. Add any visible installed known package as a fallback.
        for (Map.Entry<String, String> entry : KNOWN.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            Target target = installedTarget(context, entry.getKey());
            if (target != null) {
                result.add(target);
                seen.add(entry.getKey());
            }
        }

        result.sort(Comparator
                .comparing((Target t) -> !t.recommended)
                .thenComparing(t -> !t.known)
                .thenComparing(t -> t.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    /** Resolve a manually entered package even if it does not register as an HTTPS handler. */
    static Target installedTarget(Context context, String rawPackageName) {
        if (context == null) return null;
        String packageName = normalizePackageName(rawPackageName);
        if (!isValidPackageName(packageName) || packageName.equals(context.getPackageName())) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
            CharSequence value = pm.getApplicationLabel(app);
            String label = value == null || value.toString().isBlank()
                    ? packageName : value.toString();
            return describe(packageName, label);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isValidPackageName(String rawPackageName) {
        String value = normalizePackageName(rawPackageName);
        if (value == null || value.length() < 3 || value.length() > 255
                || value.startsWith(".") || value.endsWith(".") || !value.contains(".")) {
            return false;
        }
        String[] parts = value.split("\\.");
        if (parts.length < 2) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            char first = part.charAt(0);
            if (!(Character.isLetter(first) || first == '_')) return false;
            for (int i = 1; i < part.length(); i++) {
                char c = part.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
            }
        }
        return true;
    }

    static List<String> packageNames(List<Target> targets) {
        if (targets == null || targets.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>(targets.size());
        for (Target target : targets) {
            if (target != null && target.packageName != null) result.add(target.packageName);
        }
        return result;
    }

    private static Target describe(String packageName, String label) {
        String knownFamily = KNOWN.get(packageName);
        if (knownFamily != null) {
            return new Target(packageName, label, knownFamily, true, true);
        }
        if (looksChromium(packageName, label)) {
            return new Target(packageName, label, "Chromium 兼容候选", false, true);
        }
        return new Target(packageName, label, "其他浏览器 · 运行时验证", false, false);
    }

    private static Intent browserIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        return intent;
    }

    private static String loadLabel(PackageManager pm, String packageName, ResolveInfo info) {
        try {
            CharSequence value = info.loadLabel(pm);
            if (value != null && !value.toString().isBlank()) return value.toString();
        } catch (Throwable ignored) {}
        try {
            ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
            CharSequence value = pm.getApplicationLabel(app);
            if (value != null && !value.toString().isBlank()) return value.toString();
        } catch (Throwable ignored) {}
        return packageName;
    }

    private static String normalizePackageName(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean looksChromium(String packageName, String label) {
        String value = ((packageName == null ? "" : packageName) + " "
                + (label == null ? "" : label)).toLowerCase(Locale.ROOT);
        return value.contains("chrome")
                || value.contains("chromium")
                || value.contains("cromite")
                || value.contains("kiwi")
                || value.contains("brave")
                || value.contains("vivaldi")
                || value.contains("microsoft edge")
                || value.contains(" edge");
    }
}
