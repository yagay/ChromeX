package com.yagay.chromex;

import android.content.SharedPreferences;

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
    public static final String BYPASS_POLICY = "bypass_policy";
    public static final String BYPASS_LOCATION = "bypass_location";
    public static final String BYPASS_OPEN = "bypass_open";
    public static final String AUTO_INSTALL_APK = "auto_install_apk";
    public static final String APK_TOAST = "apk_toast";
    public static final String ALL_DOWNLOAD_TOAST = "all_download_toast";
    public static final String HIDE_TRANSLATE = "hide_translate";

    private Config() {}

    public static SharedPreferences fromService(XposedService service) {
        return service == null ? null : service.getRemotePreferences(FILE);
    }

    public static SharedPreferences fromModule(XposedModule module) {
        return module.getRemotePreferences(FILE);
    }

    public static boolean get(SharedPreferences prefs, String key) {
        if (prefs == null) return defaultValue(key);
        try {
            return prefs.getBoolean(key, defaultValue(key));
        } catch (Throwable ignored) {
            return defaultValue(key);
        }
    }

    public static boolean defaultValue(String key) {
        if (ALL_DOWNLOAD_TOAST.equals(key)) return false;
        return true;
    }
}
