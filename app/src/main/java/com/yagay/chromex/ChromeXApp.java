package com.yagay.chromex;

import android.app.Application;
import android.content.Intent;

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
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService value) {
        service = value;
        try { grantDiagnosticAccess(value.getScope()); }
        catch (Throwable ignored) {}
        for (Listener listener : LISTENERS) listener.onServiceChanged(value);
    }

    @Override
    public void onServiceDied(XposedService value) {
        if (service == value) service = null;
        for (Listener listener : LISTENERS) listener.onServiceChanged(service);
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
