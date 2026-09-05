package com.yagay.chromex;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adds a small ChromeX install entry to supported extension-store pages in LITE mode. */
final class LiteExtensionStorePageRuntime {
    private static final String[] TAB_CLASSES = {
            "org.chromium.chrome.browser.tab.TabImpl",
            "org.chromium.chrome.browser.tab.Tab"
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();

    private LiteExtensionStorePageRuntime() {}

    static boolean install(ChromeRuntime runtime, HookSupport hooks) {
        for (String className : TAB_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, runtime.classLoader);
                ArrayList<Method> methods = new ArrayList<>();
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("didFinishPageLoad")
                            && !Modifier.isAbstract(method.getModifiers())) methods.add(method);
                }
                if (methods.isEmpty()) continue;
                int i = 0;
                for (Method method : methods) {
                    String id = "chromex:plus:store-page:" + className + ':' + i++;
                    if (!INSTALLED.add(id)) continue;
                    hooks.method(method, id, chain -> {
                        Object result = chain.proceed();
                        Object tab = chain.getThisObject();
                        String url = url(tab, chain.getArgs().toArray());
                        if (tab != null && isStorePage(url)) {
                            MAIN.postDelayed(() -> decorate(tab, url, runtime.versionName), 250L);
                        }
                        return result;
                    });
                }
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void decorate(Object tab, String pageUrl, String browserVersion) {
        try {
            String extensionId = extractId(pageUrl);
            if (extensionId == null) return;
            String download = downloadUrl(pageUrl, extensionId, browserVersion);
            if (download == null) return;
            Object webContents = webContents(tab);
            if (webContents == null) return;
            String js = "(function(){if(document.getElementById('chromex-install-extension'))return;"
                    + "var b=document.createElement('button');b.id='chromex-install-extension';"
                    + "b.textContent='ChromeX 安装扩展';"
                    + "b.style.cssText='position:fixed;right:16px;bottom:18px;z-index:2147483647;"
                    + "padding:11px 16px;border:0;border-radius:18px;background:#1a73e8;color:#fff;"
                    + "font:600 14px sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.3)';"
                    + "b.onclick=function(){location.href=" + JSONObject.quote(download) + ";};"
                    + "document.documentElement.appendChild(b);})();";
            evaluate(webContents, js);
        } catch (Throwable ignored) {}
    }

    static String downloadUrl(String pageUrl, String id, String version) {
        if (pageUrl == null || id == null) return null;
        try {
            if (pageUrl.contains("chromewebstore.google.com") || pageUrl.contains("chrome.google.com/webstore")) {
                String pv = version == null || version.isBlank() ? "120.0.0.0" : version;
                String x = URLEncoder.encode("id=" + id + "&uc", StandardCharsets.UTF_8.name());
                return "https://clients2.google.com/service/update2/crx?response=redirect"
                        + "&prodversion=" + URLEncoder.encode(pv, StandardCharsets.UTF_8.name())
                        + "&acceptformat=crx2,crx3&x=" + x;
            }
            if (pageUrl.contains("microsoftedge.microsoft.com/addons")) {
                String x = URLEncoder.encode("id=" + id + "&installsource=ondemand&uc",
                        StandardCharsets.UTF_8.name());
                return "https://edge.microsoft.com/extensionwebstorebase/v1/crx?response=redirect&x=" + x;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static String extractId(String url) {
        if (url == null) return null;
        for (String part : url.split("[/\\?#=&]")) {
            if (part.length() != 32) continue;
            boolean ok = true;
            for (int i = 0; i < 32; i++) {
                char c = Character.toLowerCase(part.charAt(i));
                if (c < 'a' || c > 'p') { ok = false; break; }
            }
            if (ok) return part.toLowerCase();
        }
        return null;
    }

    private static boolean isStorePage(String url) {
        return url != null && (url.contains("chromewebstore.google.com/detail/")
                || url.contains("chrome.google.com/webstore/detail/")
                || url.contains("microsoftedge.microsoft.com/addons/detail/"));
    }

    private static String url(Object tab, Object[] args) {
        if (args != null) for (Object arg : args) {
            String value = value(arg);
            if (value != null) return value;
        }
        try {
            return value(tab.getClass().getMethod("getUrl").invoke(tab));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String value(Object value) {
        if (value instanceof String) return (String) value;
        if (value == null) return null;
        try {
            Object spec = value.getClass().getMethod("getSpec").invoke(value);
            return spec instanceof String ? (String) spec : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object webContents(Object tab) {
        try {
            return tab.getClass().getMethod("getWebContents").invoke(tab);
        } catch (Throwable t) {
            try {
                Method m = tab.getClass().getDeclaredMethod("getWebContents");
                m.setAccessible(true);
                return m.invoke(tab);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static void evaluate(Object webContents, String script) {
        try {
            for (Method method : webContents.getClass().getMethods()) {
                if (method.getName().equals("evaluateJavaScript")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == String.class) {
                    method.invoke(webContents, script, null);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
