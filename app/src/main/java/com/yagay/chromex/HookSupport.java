package com.yagay.chromex;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class HookSupport {
    interface Interceptor {
        Object intercept(XposedInterface.Chain chain) throws Throwable;
    }

    private final XposedModule module;

    HookSupport(XposedModule module) {
        this.module = module;
    }

    void exact(ClassLoader loader, String className, String methodName,
               Class<?>[] params, String id, Interceptor interceptor) {
        try {
            Class<?> type = Reflect.cls(loader, className);
            Method method = Reflect.exact(type, methodName, params);
            install(method, id, interceptor);
        } catch (Throwable t) {
            error("hook " + className + "#" + methodName, t);
        }
    }

    void all(ClassLoader loader, String className, String methodName,
             String idPrefix, Interceptor interceptor) {
        try {
            Class<?> type = Reflect.cls(loader, className);
            List<Method> methods = Reflect.named(type, methodName);
            if (methods.isEmpty()) {
                warn("no method: " + className + "#" + methodName);
                return;
            }
            int index = 0;
            for (Method method : methods) {
                install(method, idPrefix + ":" + index++, interceptor);
            }
        } catch (Throwable t) {
            error("hook all " + className + "#" + methodName, t);
        }
    }

    void method(Method method, String id, Interceptor interceptor) {
        try {
            install(method, id, interceptor);
        } catch (Throwable t) {
            error("hook method " + method, t);
        }
    }

    private void install(Method method, String id, Interceptor interceptor) {
        module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(interceptor::intercept);
        info("hooked " + method.getDeclaringClass().getName() + "#" + method.getName());
    }

    void info(String message) {
        module.log(Log.INFO, "ChromeX", message);
    }

    void warn(String message) {
        module.log(Log.WARN, "ChromeX", message);
    }

    void error(String message, Throwable t) {
        module.log(Log.ERROR, "ChromeX", message, t);
    }
}
