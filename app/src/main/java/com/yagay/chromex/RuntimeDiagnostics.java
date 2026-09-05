package com.yagay.chromex;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight diagnostics that stay enabled during normal use.
 *
 * Deep class/Dex scanning remains in {@link Diagnostics} and is still controlled by
 * Config.DIAGNOSTIC_MODE. This class only records session/build identity, hook installation,
 * hit counters and meaningful runtime events. Early records are buffered until Chrome's
 * Application/DiagnosticProvider transport is usable.
 */
final class RuntimeDiagnostics {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 160;
    private static final int MAX_PENDING = 128;
    private static final long EVENT_DEDUP_MS = 5_000L;

    private static final AtomicBoolean SESSION_STARTED = new AtomicBoolean(false);
    private static final Map<String, Long> HITS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_HIT = new ConcurrentHashMap<>();
    private static final ArrayList<EventRecord> EVENTS = new ArrayList<>();
    private static final ArrayDeque<PendingRecord> PENDING = new ArrayDeque<>();
    private static volatile long lastHitFlush;
    private static volatile boolean transportFailureLogged;

    private RuntimeDiagnostics() {}

    static void beginSession(String process, int api, String framework, String frameworkVersion) {
        if (!SESSION_STARTED.compareAndSet(false, true)) return;
        String session = "time=" + now() + "\n"
                + "process=" + process + "\n"
                + "api=" + api + "\n"
                + "framework=" + framework + " " + frameworkVersion + "\n"
                + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT + "\n"
                + "fingerprint=" + Build.FINGERPRINT + "\n"
                + "module=" + BuildConfig.VERSION_NAME
                + " run=" + BuildConfig.BUILD_RUN
                + " sha=" + BuildConfig.BUILD_SHA + "\n";
        emit(DiagnosticProvider.KIND_SESSION, session, System.currentTimeMillis());
    }

    static void hookInstalled(String id, Method method) {
        emit(DiagnosticProvider.KIND_HOOK,
                "OK   " + id + " -> " + methodSignature(method), System.currentTimeMillis());
    }

    static void hookFailed(String id, String target, Throwable error) {
        emit(DiagnosticProvider.KIND_HOOK,
                "FAIL " + id + " -> " + target + " :: " + shortError(error),
                System.currentTimeMillis());
    }

    static void hit(String id) {
        long now = System.currentTimeMillis();
        HITS.merge(id, 1L, Long::sum);
        LAST_HIT.put(id, now);
        long count = HITS.getOrDefault(id, 0L);
        if (count == 1 || count == 5 || count == 20 || now - lastHitFlush >= 5_000L) {
            flushHits(now);
        }
    }

    static void event(String level, String message) {
        if (message == null) message = "";
        long now = System.currentTimeMillis();
        String report;
        synchronized (LOCK) {
            EventRecord last = EVENTS.isEmpty() ? null : EVENTS.get(EVENTS.size() - 1);
            if (last != null && last.level.equals(level) && last.message.equals(message)
                    && now - last.lastTime <= EVENT_DEDUP_MS) {
                last.count++;
                last.lastTime = now;
            } else {
                EVENTS.add(new EventRecord(now, level, message));
                while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);
            }
            StringBuilder out = new StringBuilder();
            for (EventRecord event : EVENTS) {
                out.append(formatTime(event.firstTime)).append(' ')
                        .append(event.level).append(' ').append(event.message);
                if (event.count > 1) out.append(" [repeat=").append(event.count).append(']');
                out.append('\n');
            }
            report = out.toString();
        }
        emit(DiagnosticProvider.KIND_EVENTS, report, now);
    }

    static void flushPendingIfPossible() {
        Context context = chromeContext();
        if (context == null) return;
        synchronized (LOCK) {
            while (!PENDING.isEmpty()) {
                PendingRecord record = PENDING.peekFirst();
                if (!insert(context, record.kind, record.text, record.time)) return;
                PENDING.removeFirst();
            }
        }
    }

    private static void flushHits(long now) {
        ArrayList<String> ids = new ArrayList<>(HITS.keySet());
        ids.sort(String::compareTo);
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            long count = HITS.getOrDefault(id, 0L);
            long last = LAST_HIT.getOrDefault(id, 0L);
            out.append(id).append(" count=").append(count)
                    .append(" last=").append(last <= 0L ? "never" : formatTime(last))
                    .append('\n');
        }
        emit(DiagnosticProvider.KIND_HITS, out.toString(), now);
        lastHitFlush = now;
    }

    private static void emit(String kind, String text, long time) {
        Context context = chromeContext();
        if (context != null) {
            flushPendingIfPossible();
            if (insert(context, kind, text, time)) return;
        }
        synchronized (LOCK) {
            if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
            PENDING.addLast(new PendingRecord(kind, text, time));
        }
    }

    private static boolean insert(Context context, String kind, String text, long time) {
        try {
            ContentValues values = new ContentValues();
            values.put(DiagnosticProvider.COL_KIND, kind);
            values.put(DiagnosticProvider.COL_TEXT, text == null ? "" : text);
            values.put(DiagnosticProvider.COL_TIME, time);
            if (context.getContentResolver().insert(DiagnosticProvider.URI, values) == null) {
                throw new IllegalStateException("DiagnosticProvider returned null");
            }
            transportFailureLogged = false;
            return true;
        } catch (Throwable t) {
            if (!transportFailureLogged) {
                transportFailureLogged = true;
                android.util.Log.w("ChromeX", "runtime diagnostic IPC unavailable: "
                        + t.getClass().getSimpleName());
            }
            return false;
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

    private static String methodSignature(Method method) {
        if (method == null) return "null";
        StringBuilder out = new StringBuilder();
        out.append(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(',');
            out.append(params[i].getName());
        }
        return out.append(")->").append(method.getReturnType().getName()).toString();
    }

    private static String shortError(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        if (message == null || message.isBlank()) return t.getClass().getSimpleName();
        if (message.length() > 500) message = message.substring(0, 500);
        return t.getClass().getSimpleName() + ": " + message;
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    private static String formatTime(long time) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(time));
    }

    private static final class PendingRecord {
        final String kind;
        final String text;
        final long time;

        PendingRecord(String kind, String text, long time) {
            this.kind = kind;
            this.text = text;
            this.time = time;
        }
    }

    private static final class EventRecord {
        final long firstTime;
        final String level;
        final String message;
        long lastTime;
        int count = 1;

        EventRecord(long time, String level, String message) {
            this.firstTime = time;
            this.lastTime = time;
            this.level = level == null ? "INFO" : level;
            this.message = message;
        }
    }
}
