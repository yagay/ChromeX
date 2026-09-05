package com.yagay.chromex;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Executes the content-script subset used by the stock-Chrome LITE backend. */
final class LiteExtensionRuntime {
    private static final String[] TAB_CLASSES = {
            "org.chromium.chrome.browser.tab.TabImpl",
            "org.chromium.chrome.browser.tab.Tab"
    };
    private static final Set<String> INSTALLED_HOOKS = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LiteExtensionRuntime() {}

    static boolean install(ClassLoader loader, HookSupport hooks) {
        for (String className : TAB_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                ArrayList<Method> methods = new ArrayList<>();
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("didFinishPageLoad")
                            && !Modifier.isAbstract(method.getModifiers())) {
                        methods.add(method);
                    }
                }
                if (methods.isEmpty()) continue;
                int index = 0;
                for (Method method : methods) {
                    String id = "chromex:plus:lite:page-finished:" + className + ":" + index++;
                    if (!INSTALLED_HOOKS.add(id)) continue;
                    hooks.method(method, id, chain -> {
                        Object result = chain.proceed();
                        Object tab = chain.getThisObject();
                        String url = findUrl(tab, chain.getArgs());
                        if (tab != null && url != null) {
                            MAIN.postDelayed(() -> inject(tab, url, hooks), 80L);
                        }
                        return result;
                    });
                }
                hooks.info("LITE extension runtime attached to " + className
                        + " hooks=" + methods.size());
                return true;
            } catch (Throwable ignored) {}
        }
        hooks.warn("LITE extension runtime could not find didFinishPageLoad anchor");
        return false;
    }

    private static void inject(Object tab, String url, HookSupport hooks) {
        Context context = chromeContext();
        if (context == null || !isInjectableUrl(url)) return;
        Object webContents = getWebContents(tab);
        if (webContents == null) return;

        List<String> ids = LiteExtensionStore.listIds(context);
        int injected = 0;
        for (String id : ids) {
            LiteExtensionManifest manifest = LiteExtensionStore.readManifest(context, id);
            if (manifest == null) continue;
            File dir = LiteExtensionStore.extensionDir(context, id);
            for (LiteExtensionManifest.ContentScript content : manifest.contentScripts) {
                if (!LiteExtensionUrlMatcher.matchesAny(url, content.matches, content.excludeMatches)) {
                    continue;
                }
                String script = buildInjection(manifest, content, dir, hooks);
                if (script == null || script.isBlank()) continue;
                if (evaluate(webContents, script)) injected++;
            }
        }
        if (injected > 0) RuntimeDiagnostics.event("INFO", "LITE injected " + injected
                + " content-script group(s) url=" + trimUrl(url));
    }

    private static String buildInjection(LiteExtensionManifest manifest,
            LiteExtensionManifest.ContentScript content, File dir, HookSupport hooks) {
        StringBuilder css = new StringBuilder();
        for (String path : content.css) {
            File file = safeChild(dir, path);
            if (file == null) continue;
            try {
                css.append(LiteExtensionStore.readText(file, 1024L * 1024L)).append('\n');
            } catch (Throwable t) {
                hooks.warn("LITE CSS skipped id=" + manifest.id + " path=" + path);
            }
        }
        StringBuilder js = new StringBuilder();
        for (String path : content.js) {
            File file = safeChild(dir, path);
            if (file == null) continue;
            try {
                js.append("\n/* ").append(path.replace("*/", "* /" )).append(" */\n")
                        .append(LiteExtensionStore.readText(file, 2L * 1024L * 1024L))
                        .append("\n//# sourceURL=chromex-lite://")
                        .append(manifest.id).append('/').append(path.replace("\n", ""))
                        .append('\n');
            } catch (Throwable t) {
                hooks.warn("LITE JS skipped id=" + manifest.id + " path=" + path);
            }
        }
        if (css.length() == 0 && js.length() == 0) return null;

        String id = JSONObject.quote(manifest.id);
        String cssLiteral = JSONObject.quote(css.toString());
        return "(function(){try{"
                + "var __id=" + id + ";"
                + storagePolyfill()
                + (css.length() == 0 ? "" : "var s=document.createElement('style');"
                    + "s.setAttribute('data-chromex-extension',__id);"
                    + "s.textContent=" + cssLiteral + ";"
                    + "(document.head||document.documentElement).appendChild(s);")
                + js
                + "}catch(e){console.warn('ChromeX LITE extension '+__id,e);}})();";
    }

    private static String storagePolyfill() {
        return "var chrome=window.chrome=window.chrome||{};"
                + "chrome.runtime=chrome.runtime||{};chrome.runtime.id=__id;"
                + "chrome.storage=chrome.storage||{};"
                + "chrome.storage.local=chrome.storage.local||{"
                + "get:function(k,cb){var r={};try{if(k==null){for(var i=0;i<localStorage.length;i++){var x=localStorage.key(i),p='__cx_'+__id+'_';if(x&&x.indexOf(p)==0)r[x.slice(p.length)]=JSON.parse(localStorage.getItem(x));}}else{var a=Array.isArray(k)?k:[k];a.forEach(function(x){var v=localStorage.getItem('__cx_'+__id+'_'+x);if(v!==null)r[x]=JSON.parse(v);});}}catch(e){}if(cb)cb(r);return Promise.resolve(r);},"
                + "set:function(o,cb){try{Object.keys(o||{}).forEach(function(k){localStorage.setItem('__cx_'+__id+'_'+k,JSON.stringify(o[k]));});}catch(e){}if(cb)cb();return Promise.resolve();},"
                + "remove:function(k,cb){var a=Array.isArray(k)?k:[k];a.forEach(function(x){localStorage.removeItem('__cx_'+__id+'_'+x);});if(cb)cb();return Promise.resolve();},"
                + "clear:function(cb){var p='__cx_'+__id+'_',d=[];for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k&&k.indexOf(p)==0)d.push(k);}d.forEach(function(k){localStorage.removeItem(k);});if(cb)cb();return Promise.resolve();}"
                + "};";
    }

    private static boolean evaluate(Object webContents, String script) {
        try {
            Method best = null;
            for (Method method : webContents.getClass().getMethods()) {
                if (!method.getName().equals("evaluateJavaScript") || method.getParameterCount() != 2) {
                    continue;
                }
                if (method.getParameterTypes()[0] != String.class) continue;
                best = method;
                break;
            }
            if (best == null) {
                for (Method method : webContents.getClass().getDeclaredMethods()) {
                    if (method.getName().equals("evaluateJavaScript")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == String.class) {
                        best = method;
                        break;
                    }
                }
            }
            if (best == null) return false;
            best.setAccessible(true);
            best.invoke(webContents, script, null);
            return true;
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", "LITE evaluateJavaScript failed :: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Object getWebContents(Object tab) {
        if (tab == null) return null;
        try {
            Method m = tab.getClass().getMethod("getWebContents");
            return m.invoke(tab);
        } catch (Throwable ignored) {
            try {
                Method m = tab.getClass().getDeclaredMethod("getWebContents");
                m.setAccessible(true);
                return m.invoke(tab);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static String findUrl(Object tab, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                String value = urlValue(arg);
                if (value != null) return value;
            }
        }
        if (tab != null) {
            try {
                Method m = tab.getClass().getMethod("getUrl");
                return urlValue(m.invoke(tab));
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String urlValue(Object value) {
        if (value == null) return null;
        if (value instanceof String) {
            String s = (String) value;
            return s.contains(":") ? s : null;
        }
        try {
            Method spec = value.getClass().getMethod("getSpec");
            Object result = spec.invoke(value);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File safeChild(File root, String relative) {
        if (root == null || relative == null || relative.isBlank()) return null;
        try {
            File file = new File(root, relative.replace('\\', '/'));
            String base = root.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base) && file.isFile() ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context chromeContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isInjectableUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://");
    }

    private static String trimUrl(String url) {
        if (url == null) return "";
        return url.length() <= 180 ? url : url.substring(0, 180) + "…";
    }
}
