package com.yagay.chromex;

import java.lang.reflect.Method;

/**
 * R8/native symbols verified from Chrome 152.0.7977.75 split_chrome.apk.
 * Never use these symbols outside the Chrome 152 profile.
 */
final class Chrome152 {
    static final String HOMEPAGE = "w5c";
    static final String TAB_CREATOR = "iq4";
    static final String TAB_SELECTOR = "k3r";
    static final String DOWNLOAD_MESSAGE = "ia8";

    static final String ACTIVITY_SELECTOR_FIELD = "O2";
    static final String ACTIVITY_RECENTLY_CLOSED_FIELD = "I3";
    static final String LOAD_URL_FIELD = "a";

    static final int DANGEROUS_ACCEPT = 43;
    static final int INSECURE_ACCEPT = 2;
    static final int DUPLICATE_ACCEPT = 1;
    static final int POLICY_ACCEPT = 47;
    static final int OPEN_ACCEPT = 9;

    static final String DOWNLOAD_INFO_MIME = "c";
    static final String DOWNLOAD_INFO_NAME = "e";
    static final String DOWNLOAD_INFO_PATH = "g";

    private Chrome152() {}

    static boolean matches(ClassLoader loader) {
        if (ChromeVersion.is152()) return true;
        if (!"unknown".equals(ChromeVersion.name())) return false;
        try {
            Class<?> command = Reflect.cls(loader, "org.chromium.base.CommandLine");
            Method c = Reflect.exact(command, "c", String.class);
            if (c.getReturnType() != boolean.class) return false;
            Reflect.cls(loader, HOMEPAGE);
            Reflect.cls(loader, TAB_CREATOR);
            Reflect.cls(loader, TAB_SELECTOR);
            Reflect.cls(loader, DOWNLOAD_MESSAGE);
            Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            Reflect.cls(loader, Chrome145.DOWNLOAD_CONTROLLER);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
