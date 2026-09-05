package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Source decision for Chromium's Android download-location prompt preference. */
final class DownloadLocationPolicyBinding {
    private static final String PROMPT_STATUS =
            "org.chromium.chrome.browser.download.DownloadPromptStatus";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final Method getter;

    DownloadLocationPolicyBinding(ChromeRuntime runtime, HookSupport hooks,
                                  SharedPreferences prefs, Method getter) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
        this.getter = getter;
    }

    boolean install() {
        if (getter == null) return false;
        Integer dontShow = dontShowValue(runtime.classLoader);
        if (dontShow == null) {
            hooks.warn("download location source policy unavailable: DONT_SHOW unresolved");
            return false;
        }
        hooks.method(getter, "chromex:download-location:policy", chain -> {
            if (Config.get(prefs, Config.BYPASS_LOCATION)) return dontShow;
            return chain.proceed();
        });
        hooks.info("download location source policy bound: "
                + getter.getDeclaringClass().getName() + '#' + getter.getName());
        return true;
    }

    static Integer dontShowValue(ClassLoader loader) {
        try {
            Class<?> type = Reflect.cls(loader, PROMPT_STATUS);
            Field field = type.getDeclaredField("DONT_SHOW");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
