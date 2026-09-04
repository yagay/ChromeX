package com.yagay.chromex;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostics running inside Chrome.
 *
 * Important: XposedModule#getRemotePreferences() is read-only in hooked apps. This class only
 * reads feature/diagnostic settings from RemotePreferences and sends diagnostic output to
 * DiagnosticProvider, which stores it under the ChromeX app UID.
 */
final class Diagnostics {
    static final String KEY_SESSION = "_diag_session";
    static final String KEY_SCAN_REPORT = "_diag_scan_report";
    static final String KEY_HOOK_REPORT = "_diag_hook_report";
    static final String KEY_HIT_REPORT = "_diag_hit_report";
    static final String KEY_EVENT_REPORT = "_diag_event_report";
    static final String KEY_LAST_SCAN = "_diag_last_scan";

    private static final Object LOCK = new Object();
    private static final int MAX_REPORT_CHARS = 180_000;
    private static final int MAX_EVENTS = 120;
    private static final int MAX_SHORT_CLASSES = 7000;
    private static final Map<String, Long> HITS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_HIT = new ConcurrentHashMap<>();
    private static final List<String> EVENTS = new ArrayList<>();
    private static volatile long lastHitFlush;
    private static volatile boolean providerFailureLogged;

    private Diagnostics() {}

    static void beginSession(SharedPreferences prefs, String process, int api,
                             String framework, String frameworkVersion) {
        if (!enabled(prefs)) return;
        try {
            synchronized (LOCK) {
                HITS.clear();
                LAST_HIT.clear();
                EVENTS.clear();
                lastHitFlush = 0L;
            }
            String session = "time=" + now() + "\n"
                    + "process=" + process + "\n"
                    + "api=" + api + "\n"
                    + "framework=" + framework + " " + frameworkVersion + "\n"
                    + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT + "\n"
                    + "fingerprint=" + Build.FINGERPRINT + "\n"
                    + "chrome=" + chromeVersion() + "\n";
            emit(DiagnosticProvider.KIND_SESSION, session, System.currentTimeMillis());
        } catch (Throwable t) {
            safeLog("beginSession", t);
        }
    }

    static void scheduleScan(SharedPreferences prefs, ClassLoader loader) {
        if (!enabled(prefs)) return;
        try {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(2500L);
                    scan(prefs, loader);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable scanError) {
                    event(prefs, "SCAN", "fatal: " + shortError(scanError));
                }
            }, "ChromeX-hook-locator");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            safeLog("scheduleScan", t);
        }
    }

    static void hookInstalled(SharedPreferences prefs, String id, Method method) {
        if (!enabled(prefs)) return;
        try {
            emit(DiagnosticProvider.KIND_HOOK,
                    "OK   " + id + " -> " + methodSignature(method),
                    System.currentTimeMillis());
        } catch (Throwable t) {
            safeLog("hookInstalled", t);
        }
    }

    static void hookFailed(SharedPreferences prefs, String id, String target, Throwable error) {
        if (!enabled(prefs)) return;
        try {
            emit(DiagnosticProvider.KIND_HOOK,
                    "FAIL " + id + " -> " + target + " :: " + shortError(error),
                    System.currentTimeMillis());
        } catch (Throwable t) {
            safeLog("hookFailed", t);
        }
    }

    static void hit(SharedPreferences prefs, String id) {
        if (!enabled(prefs)) return;
        try {
            long time = System.currentTimeMillis();
            HITS.merge(id, 1L, Long::sum);
            LAST_HIT.put(id, time);
            long count = HITS.getOrDefault(id, 0L);
            if (count == 1 || count == 5 || count == 20 || time - lastHitFlush >= 5000L) {
                flushHits(time);
            }
        } catch (Throwable t) {
            safeLog("hit", t);
        }
    }

    static void event(SharedPreferences prefs, String level, String message) {
        if (!enabled(prefs)) return;
        try {
            String report;
            synchronized (LOCK) {
                EVENTS.add(now() + " " + level + " " + message);
                while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);
                StringBuilder out = new StringBuilder();
                for (String line : EVENTS) out.append(line).append('\n');
                report = out.toString();
            }
            emit(DiagnosticProvider.KIND_EVENTS, report, System.currentTimeMillis());
        } catch (Throwable t) {
            safeLog("event", t);
        }
    }

    static void scan(SharedPreferences prefs, ClassLoader loader) {
        if (!enabled(prefs)) return;
        try {
            StringBuilder out = new StringBuilder(64_000);
            out.append("ChromeX automatic hook locator\n");
            out.append("scan_time=").append(now()).append('\n');
            out.append("chrome_version=").append(chromeVersion()).append('\n');
            out.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
            out.append("classloader=")
                    .append(loader == null ? "null" : loader.getClass().getName())
                    .append("\n\n");

            String[] stable = {
                    "org.chromium.base.CommandLine",
                    Chrome145.ACTIVITY,
                    Chrome145.TAB_MODEL,
                    Chrome145.TAB_MODEL_API,
                    Chrome145.CHROME_TAB_CREATOR,
                    Chrome145.LOAD_URL_PARAMS,
                    Chrome145.HOMEPAGE_MANAGER,
                    Chrome145.GURL,
                    Chrome145.PROFILE_MANAGER,
                    Chrome145.DOWNLOAD_INFO,
                    Chrome145.DOWNLOAD_CONTROLLER,
                    Chrome145.DOWNLOAD_MANAGER_SERVICE,
                    Chrome145.DOWNLOAD_UTILS,
                    "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                    "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                    "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                    "org.chromium.chrome.browser.download.DownloadDialogBridge",
                    "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                    Chrome145.OFFLINE_ITEM,
                    Chrome145.OFFLINE_VISUALS,
                    Chrome145.PROPERTY_MODEL,
                    Chrome145.TRANSLATE_MESSAGE,
                    Chrome145.NATIVE
            };

            out.append("=== STABLE CLASS PROBE ===\n");
            for (String name : stable) probeClass(loader, name, out);

            out.append("\n=== LEGACY 145 SYMBOL PROBE ===\n");
            for (String name : new String[]{Chrome145.COMMAND_FLAGS, Chrome145.SELECTOR,
                    Chrome145.HOMEPAGE, Chrome145.CLOSE_ALL_RUNNABLE, Chrome145.TAB_CREATOR,
                    Chrome145.DOWNLOAD_EVENT_RUNNABLE, Chrome145.OFFLINE_COMPLETE,
                    Chrome145.OPEN_DOWNLOAD_REQUEST, Chrome145.DOWNLOAD_MESSAGE,
                    Chrome145.MESSAGE_DISPATCHER}) {
                probeClass(loader, name, out);
            }

            out.append("\n=== AUTOMATIC R8 CANDIDATES ===\n");
            try {
                locateShortR8Classes(loader, out);
            } catch (Throwable t) {
                out.append("R8_SCAN_FAILED: ").append(shortError(t)).append('\n');
            }

            out.append("\n=== DOWNLOADUTILS PARAMETER CLUES ===\n");
            try {
                Class<?> utils = Reflect.cls(loader, Chrome145.DOWNLOAD_UTILS);
                for (Method m : utils.getDeclaredMethods()) {
                    String sig = methodSignature(m);
                    if (sig.contains("Download") || hasShortTopLevelParameter(m)) {
                        out.append(sig).append('\n');
                    }
                }
            } catch (Throwable t) {
                out.append("DownloadUtils inspect failed: ").append(shortError(t)).append('\n');
            }

            String report = trim(out.toString(), MAX_REPORT_CHARS);
            emit(DiagnosticProvider.KIND_SCAN, report, System.currentTimeMillis());
            event(prefs, "SCAN", "completed, chars=" + report.length());
        } catch (Throwable t) {
            safeLog("scan", t);
            event(prefs, "SCAN", "fatal: " + shortError(t));
        }
    }

    private static void flushHits(long time) {
        try {
            ArrayList<String> ids = new ArrayList<>(HITS.keySet());
            ids.sort(String::compareTo);
            StringBuilder out = new StringBuilder();
            for (String id : ids) {
                long count = HITS.getOrDefault(id, 0L);
                long last = LAST_HIT.getOrDefault(id, 0L);
                out.append(id)
                        .append(" count=").append(count)
                        .append(" last=").append(last <= 0L ? "never" : formatTime(last))
                        .append('\n');
            }
            emit(DiagnosticProvider.KIND_HITS, out.toString(), time);
            lastHitFlush = time;
        } catch (Throwable t) {
            safeLog("flushHits", t);
        }
    }

    private static boolean enabled(SharedPreferences prefs) {
        try {
            return Config.get(prefs, Config.DIAGNOSTIC_MODE);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void emit(String kind, String text, long time) {
        try {
            Context context = chromeContext();
            if (context == null) throw new IllegalStateException("Chrome application context unavailable");
            ContentValues values = new ContentValues();
            values.put(DiagnosticProvider.COL_KIND, kind);
            values.put(DiagnosticProvider.COL_TEXT, text == null ? "" : text);
            values.put(DiagnosticProvider.COL_TIME, time);
            if (context.getContentResolver().insert(DiagnosticProvider.URI, values) == null) {
                throw new IllegalStateException("DiagnosticProvider returned null");
            }
        } catch (Throwable t) {
            if (!providerFailureLogged) {
                providerFailureLogged = true;
                safeLog("diagnostic IPC unavailable", t);
            }
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

    private static void probeClass(ClassLoader loader, String name, StringBuilder out) {
        try {
            Class<?> c = Class.forName(name, false, loader);
            out.append("CLASS OK      ").append(name).append('\n');
            Method[] methods = c.getDeclaredMethods();
            int shown = 0;
            for (Method m : methods) {
                if (Chrome145.NATIVE.equals(name) && !isNativeClue(m)) continue;
                if (shown++ >= 80) {
                    out.append("  ... methods truncated ...\n");
                    break;
                }
                out.append("  ").append(methodSignature(m)).append('\n');
            }
            Field[] fields = c.getDeclaredFields();
            int fieldShown = 0;
            for (Field f : fields) {
                if (fieldShown++ >= 30) break;
                out.append("  FIELD ").append(Modifier.toString(f.getModifiers())).append(' ')
                        .append(f.getType().getName()).append(' ').append(f.getName()).append('\n');
            }
        } catch (Throwable t) {
            out.append("CLASS MISSING ").append(name).append(" :: ")
                    .append(shortError(t)).append('\n');
        }
    }

    private static boolean isNativeClue(Method m) {
        String n = m.getName();
        return n.equals("VJO") || n.equals("VJJZ") || n.equals("VJOZ")
                || n.equals("VIOOOOOOO") || n.startsWith("VJ") || n.startsWith("VI");
    }

    private static void locateShortR8Classes(ClassLoader loader, StringBuilder out) throws Exception {
        List<String> names = dexClassNames(loader);
        Set<String> shortNames = new LinkedHashSet<>();
        for (String name : names) {
            if (name.indexOf('.') >= 0 || name.indexOf('$') >= 0) continue;
            if (name.length() < 1 || name.length() > 5) continue;
            shortNames.add(name);
            if (shortNames.size() >= MAX_SHORT_CLASSES) break;
        }
        out.append("dex_classes=").append(names.size())
                .append(" short_top_level_scanned=").append(shortNames.size()).append('\n');

        LinkedHashMap<String, List<String>> hits = new LinkedHashMap<>();
        hits.put("COMMAND_FLAG bool(String)", new ArrayList<>());
        hits.put("SELECTOR (boolean)->TabModel", new ArrayList<>());
        hits.put("HOMEPAGE (boolean)->GURL", new ArrayList<>());
        hits.put("TAB_CREATOR contains LoadUrlParams", new ArrayList<>());
        hits.put("OFFLINE_COMPLETE (OfflineItem,OfflineItemVisuals)", new ArrayList<>());
        hits.put("DOWNLOAD_MESSAGE (OfflineItem,boolean,boolean,boolean)", new ArrayList<>());
        hits.put("MESSAGE_DISPATCHER (PropertyModel,boolean)", new ArrayList<>());

        for (String name : shortNames) {
            Class<?> c;
            try {
                c = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : methods) {
                String[] p = parameterNames(m);
                String r = safeTypeName(m.getReturnType());
                String sig = name + " :: " + methodSignature(m);
                if (p.length == 1 && "java.lang.String".equals(p[0]) && "boolean".equals(r)) {
                    addCandidate(hits.get("COMMAND_FLAG bool(String)"), sig, 35);
                }
                if (p.length == 1 && "boolean".equals(p[0])
                        && (Chrome145.TAB_MODEL_API.equals(r) || Chrome145.TAB_MODEL.equals(r))) {
                    addCandidate(hits.get("SELECTOR (boolean)->TabModel"), sig, 20);
                }
                if (p.length == 1 && "boolean".equals(p[0]) && Chrome145.GURL.equals(r)) {
                    addCandidate(hits.get("HOMEPAGE (boolean)->GURL"), sig, 20);
                }
                if (contains(p, Chrome145.LOAD_URL_PARAMS)) {
                    addCandidate(hits.get("TAB_CREATOR contains LoadUrlParams"), sig, 30);
                }
                if (matches(p, Chrome145.OFFLINE_ITEM, Chrome145.OFFLINE_VISUALS)) {
                    addCandidate(hits.get("OFFLINE_COMPLETE (OfflineItem,OfflineItemVisuals)"), sig, 20);
                }
                if (matches(p, Chrome145.OFFLINE_ITEM, "boolean", "boolean", "boolean")) {
                    addCandidate(hits.get("DOWNLOAD_MESSAGE (OfflineItem,boolean,boolean,boolean)"), sig, 20);
                }
                if (matches(p, Chrome145.PROPERTY_MODEL, "boolean")) {
                    addCandidate(hits.get("MESSAGE_DISPATCHER (PropertyModel,boolean)"), sig, 20);
                }
            }
        }

        for (Map.Entry<String, List<String>> e : hits.entrySet()) {
            out.append("\n[").append(e.getKey()).append("] candidates=")
                    .append(e.getValue().size()).append('\n');
            for (String line : e.getValue()) out.append("  ").append(line).append('\n');
        }
    }

    private static List<String> dexClassNames(ClassLoader loader) throws Exception {
        ArrayList<String> result = new ArrayList<>();
        Object pathList = Reflect.field(loader.getClass(), "pathList").get(loader);
        Object elementsObject = Reflect.field(pathList.getClass(), "dexElements").get(pathList);
        if (!(elementsObject instanceof Object[])) return result;
        for (Object element : (Object[]) elementsObject) {
            if (element == null) continue;
            Object dexFile;
            try {
                dexFile = Reflect.field(element.getClass(), "dexFile").get(element);
            } catch (Throwable ignored) {
                continue;
            }
            if (dexFile == null) continue;
            Method entries = dexFile.getClass().getMethod("entries");
            Object value = entries.invoke(dexFile);
            if (!(value instanceof Enumeration)) continue;
            Enumeration<?> en = (Enumeration<?>) value;
            while (en.hasMoreElements()) {
                Object next = en.nextElement();
                if (next != null) result.add(String.valueOf(next));
            }
        }
        return result;
    }

    private static boolean hasShortTopLevelParameter(Method m) {
        try {
            for (Class<?> p : m.getParameterTypes()) {
                String n = p.getName();
                if (n.indexOf('.') < 0 && n.indexOf('$') < 0 && n.length() <= 5) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String[] parameterNames(Method m) {
        Class<?>[] types = m.getParameterTypes();
        String[] out = new String[types.length];
        for (int i = 0; i < types.length; i++) out[i] = safeTypeName(types[i]);
        return out;
    }

    private static boolean contains(String[] values, String wanted) {
        for (String value : values) if (wanted.equals(value)) return true;
        return false;
    }

    private static boolean matches(String[] actual, String... wanted) {
        if (actual.length != wanted.length) return false;
        for (int i = 0; i < actual.length; i++) if (!wanted[i].equals(actual[i])) return false;
        return true;
    }

    private static void addCandidate(List<String> list, String value, int max) {
        if (list.size() < max) list.add(value);
    }

    private static String methodSignature(Method m) {
        StringBuilder s = new StringBuilder();
        s.append(Modifier.toString(m.getModifiers())).append(' ')
                .append(safeTypeName(m.getReturnType())).append(' ')
                .append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) s.append(',');
            s.append(safeTypeName(p[i]));
        }
        return s.append(')').toString();
    }

    private static String safeTypeName(Class<?> c) {
        try {
            return c.getName();
        } catch (Throwable ignored) {
            return String.valueOf(c);
        }
    }

    private static String shortError(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        if (message == null || message.isBlank()) return t.getClass().getName();
        return t.getClass().getName() + ": " + message.replace('\n', ' ');
    }

    private static String trim(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "\n... diagnostic report truncated ...\n";
    }

    private static String chromeVersion() {
        try {
            Context context = chromeContext();
            if (context == null) return "unknown";
            PackageInfo info = context.getPackageManager().getPackageInfo(Chrome145.PACKAGE, 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String now() {
        return formatTime(System.currentTimeMillis());
    }

    private static String formatTime(long value) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(value));
    }

    private static void safeLog(String where, Throwable t) {
        try {
            Log.w("ChromeXDiag", where + " failed: " + shortError(t));
        } catch (Throwable ignored) {}
    }
}
