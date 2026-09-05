package com.yagay.chromex;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class HookSupport {
    interface Interceptor {
        Object intercept(XposedInterface.Chain chain) throws Throwable;
    }

    private final XposedModule module;
    private final SharedPreferences prefs;

    HookSupport(XposedModule module, SharedPreferences prefs) {
        this.module = module;
        this.prefs = prefs;
    }

    void exact(ClassLoader loader, String className, String methodName,
               Class<?>[] params, String id, Interceptor interceptor) {
        try {
            Class<?> type = Reflect.cls(loader, className);
            Method method = Reflect.exact(type, methodName, params);
            install(method, id, interceptor);
        } catch (Throwable t) {
            Diagnostics.hookFailed(prefs, id, className + "#" + methodName, t);
            error("hook " + className + "#" + methodName, t);
        }
    }

    void all(ClassLoader loader, String className, String methodName,
             String idPrefix, Interceptor interceptor) {
        try {
            Class<?> type = Reflect.cls(loader, className);
            List<Method> methods = Reflect.named(type, methodName);
            if (methods.isEmpty()) {
                Diagnostics.hookFailed(prefs, idPrefix, className + "#" + methodName,
                        new NoSuchMethodException("no matching overload"));
                warn("no method: " + className + "#" + methodName);
                return;
            }
            int index = 0;
            for (Method method : methods) {
                install(method, idPrefix + ":" + index++, interceptor);
            }
        } catch (Throwable t) {
            Diagnostics.hookFailed(prefs, idPrefix, className + "#" + methodName, t);
            error("hook all " + className + "#" + methodName, t);
        }
    }

    void method(Method method, String id, Interceptor interceptor) {
        try {
            install(method, id, interceptor);
        } catch (Throwable t) {
            Diagnostics.hookFailed(prefs, id, String.valueOf(method), t);
            error("hook method " + method, t);
        }
    }

    private void install(Method method, String id, Interceptor interceptor) {
        if (Modifier.isAbstract(method.getModifiers())) {
            warn("skip abstract hook " + id + " -> "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
            return;
        }
        module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Diagnostics.hit(prefs, id);
                    return interceptor.intercept(chain);
                });
        Diagnostics.hookInstalled(prefs, id, method);
        info("hooked " + method.getDeclaringClass().getName() + "#" + method.getName());
    }

    void info(String message) {
        Diagnostics.event(prefs, "INFO", message);
        module.log(Log.INFO, "ChromeX", message);
    }

    void warn(String message) {
        Diagnostics.event(prefs, "WARN", message);
        module.log(Log.WARN, "ChromeX", message);
    }

    void error(String message, Throwable t) {
        Diagnostics.event(prefs, "ERROR", message + " :: "
                + (t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage()));
        module.log(Log.ERROR, "ChromeX", message, t);
    }
}
