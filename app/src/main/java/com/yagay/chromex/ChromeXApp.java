package com.yagay.chromex;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ChromeXApp extends Application implements XposedServiceHelper.OnServiceListener {
    public interface Listener {
        void onServiceChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> foregroundActivity = new WeakReference<>(null);

    public static XposedService getService() {
        return service;
    }

    public static void addListener(Listener listener, boolean notifyNow) {
        LISTENERS.add(listener);
        if (notifyNow) listener.onServiceChanged(service);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        grantDiagnosticAccess(Collections.singletonList(Chrome145.PACKAGE));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                foregroundActivity = new WeakReference<>(activity);
                attachPersistentEntriesSoon(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {
                Activity current = foregroundActivity.get();
                if (current == activity) foregroundActivity = new WeakReference<>(null);
            }
        });
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService value) {
        service = value;
        try { grantDiagnosticAccess(value.getScope()); }
        catch (Throwable ignored) {}
        for (Listener listener : LISTENERS) listener.onServiceChanged(value);
        Activity activity = foregroundActivity.get();
        if (activity != null) attachPersistentEntriesSoon(activity);
    }

    @Override
    public void onServiceDied(XposedService value) {
        if (service == value) service = null;
        for (Listener listener : LISTENERS) listener.onServiceChanged(service);
        Activity activity = foregroundActivity.get();
        if (activity != null) attachPersistentEntriesSoon(activity);
    }

    private void attachPersistentEntriesSoon(Activity activity) {
        main.post(() -> attachPersistentEntries(activity));
        main.postDelayed(() -> attachPersistentEntries(activity), 350L);
    }

    private static void attachPersistentEntries(Activity activity) {
        AluminiumInstallerEntry.attach(activity);
        OverwriteConfirmationEntry.attach(activity);
    }

    private void grantDiagnosticAccess(Collection<String> packages) {
        if (packages == null) return;
        int write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int readWritePrefix = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | write | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        for (String packageName : packages) {
            if (packageName == null || packageName.isBlank()) continue;
            try {
                grantUriPermission(packageName, DiagnosticProvider.URI, write);
                grantUriPermission(packageName, DiagnosticProvider.CACHE_URI, readWritePrefix);
            } catch (Throwable ignored) {}
        }
    }
}
