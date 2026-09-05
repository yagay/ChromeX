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

/** Discovers installed Chromium-family browsers and describes known compatibility targets. */
final class ChromiumTargets {
    static final class Target {
        final String packageName;
        final String label;
        final String family;
        final boolean known;

        Target(String packageName, String label, String family, boolean known) {
            this.packageName = packageName;
            this.label = label;
            this.family = family;
            this.known = known;
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

    static List<Target> discover(Context context) {
        if (context == null) return Collections.emptyList();
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.example.com/"));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);

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
            String knownFamily = KNOWN.get(packageName);
            if (knownFamily != null) {
                result.add(new Target(packageName, label, knownFamily, true));
                continue;
            }
            if (!looksChromium(packageName, label)) continue;
            result.add(new Target(packageName, label, "Chromium 兼容候选", false));
        }

        // Known targets may not appear in the browser resolution result if disabled/default filters
        // differ on an OEM build. Add any visible installed known package as a fallback.
        for (Map.Entry<String, String> entry : KNOWN.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            try {
                ApplicationInfo app = pm.getApplicationInfo(entry.getKey(), 0);
                String label = String.valueOf(pm.getApplicationLabel(app));
                result.add(new Target(entry.getKey(), label, entry.getValue(), true));
                seen.add(entry.getKey());
            } catch (Throwable ignored) {}
        }

        result.sort(Comparator
                .comparing((Target t) -> !t.known)
                .thenComparing(t -> t.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    static List<String> packageNames(List<Target> targets) {
        if (targets == null || targets.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>(targets.size());
        for (Target target : targets) {
            if (target != null && target.packageName != null) result.add(target.packageName);
        }
        return result;
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
