package com.yagay.chromex;

import android.app.Application;

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
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService value) {
        service = value;
        for (Listener listener : LISTENERS) listener.onServiceChanged(value);
    }

    @Override
    public void onServiceDied(XposedService value) {
        if (service == value) service = null;
        for (Listener listener : LISTENERS) listener.onServiceChanged(service);
    }
}
