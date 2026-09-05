package com.yagay.chromex;

import java.util.Locale;

final class ExtensionPackagePolicy {
    private ExtensionPackagePolicy() {}

    static boolean isCrx(String mime, String nameOrPath) {
        String value = nameOrPath == null ? "" : nameOrPath.toLowerCase(Locale.ROOT);
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        int h = value.indexOf('#');
        if (h >= 0) value = value.substring(0, h);
        if (value.endsWith(".crx")) return true;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return m.contains("chrome-extension") || m.contains("x-chrome-extension");
    }
}
