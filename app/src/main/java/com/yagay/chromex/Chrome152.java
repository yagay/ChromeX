package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Symbols verified from Chrome 152.0.7977.75 split_chrome.apk. The release profile is deliberately
 * exact-build scoped because R8 names and J.N selector numbers may change even inside one Chrome
 * major. Other 152 builds fall back to the structural adaptive resolver.
 */
final class Chrome152 {
    static final String VERIFIED_VERSION = "152.0.7977.75";

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

    static boolean matches(ChromeRuntime runtime) {
        if (runtime == null || !VERIFIED_VERSION.equals(runtime.versionName)) return false;
        ClassLoader loader = runtime.classLoader;
        try {
            Class<?> command = Reflect.cls(loader, "org.chromium.base.CommandLine");
            Method commandMethod = Reflect.exact(command, "c", String.class);
            if (commandMethod.getReturnType() != boolean.class) return false;

            Class<?> gurl = Reflect.cls(loader, Chrome145.GURL);
            Class<?> homepage = Reflect.cls(loader, HOMEPAGE);
            Method singleton = Reflect.exact(homepage, "d");
            Method ntpGetter = Reflect.exact(homepage, "e", boolean.class);
            Method homepageGetter = Reflect.exact(homepage, "b", boolean.class, boolean.class);
            if (!Modifier.isStatic(singleton.getModifiers())
                    || singleton.getReturnType() != homepage
                    || ntpGetter.getReturnType() != gurl
                    || Modifier.isStatic(homepageGetter.getModifiers())
                    || homepageGetter.getReturnType() != gurl) return false;

            Class<?> loadUrl = Reflect.cls(loader, Chrome145.LOAD_URL_PARAMS);
            Class<?> creator = Reflect.cls(loader, TAB_CREATOR);
            boolean creatorOk = false;
            for (Method method : creator.getDeclaredMethods()) {
                if (!"m".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length > 0 && params[0] == loadUrl) {
                    creatorOk = true;
                    break;
                }
            }
            if (!creatorOk) return false;

            Class<?> selector = Reflect.cls(loader, TAB_SELECTOR);
            Class<?> tabModel = Reflect.cls(loader, Chrome145.TAB_MODEL_API);
            Method select = Reflect.exact(selector, "k", boolean.class);
            if (!tabModel.isAssignableFrom(select.getReturnType())) return false;

            Reflect.cls(loader, DOWNLOAD_MESSAGE);
            Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            Reflect.cls(loader, Chrome145.DOWNLOAD_CONTROLLER);
            Reflect.cls(loader, Chrome145.NATIVE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
