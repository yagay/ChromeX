package com.yagay.chromex;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedService;

public final class Config {
    public static final String FILE = "chromex";

    public static final String CLEAN_START = "clean_start";
    public static final String NEWTAB_HOME = "newtab_home";
    public static final String CLEAR_CLOSED_TABS = "clear_closed_tabs";
    public static final String BYPASS_DANGEROUS = "bypass_dangerous";
    public static final String BYPASS_INSECURE = "bypass_insecure";
    public static final String BYPASS_DUPLICATE = "bypass_duplicate";
    public static final String OVERWRITE_DUPLICATE = "overwrite_duplicate";
    public static final String OVERWRITE_CONFIRM_DUPLICATE = "overwrite_confirm_duplicate";
    public static final String BYPASS_POLICY = "bypass_policy";
    public static final String BYPASS_LOCATION = "bypass_location";
    public static final String BYPASS_OPEN = "bypass_open";

    // Keep the original preference key for APK so existing users retain their choice after the
    // feature is expanded from "auto install APK" to "auto open selected download types".
    public static final String AUTO_OPEN_APK = "auto_install_apk";
    @Deprecated public static final String AUTO_INSTALL_APK = AUTO_OPEN_APK;
    public static final String AUTO_OPEN_APP_BUNDLE = "auto_open_app_bundle";
    public static final String AUTO_OPEN_PDF = "auto_open_pdf";
    public static final String AUTO_OPEN_ARCHIVE = "auto_open_archive";
    public static final String AUTO_OPEN_DOCUMENT = "auto_open_document";
    public static final String AUTO_OPEN_SPREADSHEET = "auto_open_spreadsheet";
    public static final String AUTO_OPEN_PRESENTATION = "auto_open_presentation";
    public static final String AUTO_OPEN_TEXT = "auto_open_text";
    public static final String AUTO_OPEN_IMAGE = "auto_open_image";
    public static final String AUTO_OPEN_VIDEO = "auto_open_video";
    public static final String AUTO_OPEN_AUDIO = "auto_open_audio";
    public static final String AUTO_OPEN_EBOOK = "auto_open_ebook";

    /** Browser packages explicitly selected from ChromeX or adopted from the real LSPosed scope. */
    public static final String DYNAMIC_TARGETS = "dynamic_chromium_targets";

    public static final String APK_TOAST = "apk_toast";
    public static final String ALL_DOWNLOAD_TOAST = "all_download_toast";
    public static final String HIDE_TRANSLATE = "hide_translate";
    public static final String DIAGNOSTIC_MODE = "diagnostic_mode";

    public static final String[] AUTO_OPEN_KEYS = {
            AUTO_OPEN_APK,
            AUTO_OPEN_APP_BUNDLE,
            AUTO_OPEN_PDF,
            AUTO_OPEN_ARCHIVE,
            AUTO_OPEN_DOCUMENT,
            AUTO_OPEN_SPREADSHEET,
            AUTO_OPEN_PRESENTATION,
            AUTO_OPEN_TEXT,
            AUTO_OPEN_IMAGE,
            AUTO_OPEN_VIDEO,
            AUTO_OPEN_AUDIO,
            AUTO_OPEN_EBOOK
    };

    private Config() {}

    public static SharedPreferences fromService(XposedService service) {
        if (service == null) return null;
        SharedPreferences prefs = service.getRemotePreferences(FILE);
        // A user may add an unknown browser directly from LSPosed instead of ChromeX. Adopt every
        // non-built-in package already present in the real module scope so the runtime entry gate
        // accepts it too. Chromium core-class validation still decides whether hooks are installed.
        try {
            ArrayList<String> adopted = new ArrayList<>();
            for (String packageName : service.getScope()) {
                if (packageName != null && !ChromiumTargets.isKnownPackage(packageName)) {
                    adopted.add(packageName);
                }
            }
            addDynamicTargets(prefs, adopted);
        } catch (Throwable ignored) {}
        return prefs;
    }

    public static SharedPreferences fromModule(XposedModule module) {
        return module.getRemotePreferences(FILE);
    }

    public static boolean get(SharedPreferences prefs, String key) {
        if (prefs == null) return defaultValue(key);
        try {
            // Overwrite owns duplicate-conflict confirmation when enabled. Suppress the ordinary
            // duplicate bypass so two interceptors cannot race on the same native callback.
            if (BYPASS_DUPLICATE.equals(key)
                    && prefs.getBoolean(OVERWRITE_DUPLICATE, defaultValue(OVERWRITE_DUPLICATE))) {
                return false;
            }
            // When the user asks to keep Chromium's duplicate confirmation, the confirmation
            // policy temporarily suppresses only the auto-confirm overwrite interceptor on the
            // current callback thread. Completion/history normalization remains enabled.
            if (OVERWRITE_DUPLICATE.equals(key) && OverwriteConfirmationPolicy.isSuppressed()) {
                return false;
            }
            return prefs.getBoolean(key, defaultValue(key));
        } catch (Throwable ignored) {
            return defaultValue(key);
        }
    }

    static boolean stored(SharedPreferences prefs, String key) {
        if (prefs == null) return defaultValue(key);
        try { return prefs.getBoolean(key, defaultValue(key)); }
        catch (Throwable ignored) { return defaultValue(key); }
    }

    public static Set<String> dynamicTargets(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptySet();
        try {
            Set<String> stored = prefs.getStringSet(DYNAMIC_TARGETS, Collections.emptySet());
            return stored == null ? Collections.emptySet() : new HashSet<>(stored);
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    public static boolean isDynamicTarget(SharedPreferences prefs, String packageName) {
        return packageName != null && dynamicTargets(prefs).contains(packageName);
    }

    public static void addDynamicTargets(SharedPreferences prefs, Collection<String> packages) {
        updateDynamicTargets(prefs, packages, true);
    }

    public static void removeDynamicTargets(SharedPreferences prefs, Collection<String> packages) {
        updateDynamicTargets(prefs, packages, false);
    }

    private static void updateDynamicTargets(SharedPreferences prefs, Collection<String> packages,
                                             boolean add) {
        if (prefs == null || packages == null || packages.isEmpty()) return;
        try {
            Set<String> next = new HashSet<>(dynamicTargets(prefs));
            if (add) next.addAll(packages);
            else next.removeAll(packages);
            prefs.edit().putStringSet(DYNAMIC_TARGETS, next).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean defaultValue(String key) {
        // Safety-sensitive actions and every automatic file launch are explicit opt-in on a fresh
        // install. Existing APK users keep their old value because AUTO_OPEN_APK reuses that key.
        if (BYPASS_DANGEROUS.equals(key)
                || BYPASS_INSECURE.equals(key)
                || BYPASS_POLICY.equals(key)
                || BYPASS_OPEN.equals(key)
                || isAutoOpenKey(key)
                || DIAGNOSTIC_MODE.equals(key)
                || ALL_DOWNLOAD_TOAST.equals(key)
                || OVERWRITE_DUPLICATE.equals(key)
                || OVERWRITE_CONFIRM_DUPLICATE.equals(key)) {
            return false;
        }
        return true;
    }

    private static boolean isAutoOpenKey(String key) {
        if (key == null) return false;
        for (String value : AUTO_OPEN_KEYS) if (value.equals(key)) return true;
        return false;
    }
}
