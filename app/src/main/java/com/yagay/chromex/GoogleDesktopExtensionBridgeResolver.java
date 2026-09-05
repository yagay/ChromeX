package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Resolves the official Chromium Desktop Android extension Java bridge without fixed R8 names. */
final class GoogleDesktopExtensionBridgeResolver {
    static final String ACTIONS_BRIDGE =
            "org.chromium.chrome.browser.ui.extensions.ExtensionActionsBridge";

    static final class Binding {
        final Class<?> actionsBridge;
        final Method extensionsEnabled;
        final String detail;

        Binding(Class<?> actionsBridge, Method extensionsEnabled, String detail) {
            this.actionsBridge = actionsBridge;
            this.extensionsEnabled = extensionsEnabled;
            this.detail = detail;
        }

        boolean canUnlock() {
            return actionsBridge != null && extensionsEnabled != null;
        }

        String diagnosticsText() {
            return "googleActionsBridge=" + (actionsBridge == null ? "missing" : actionsBridge.getName())
                    + "\nextensionsEnabled=" + describe(extensionsEnabled)
                    + "\nresolver=" + detail + "\n";
        }
    }

    private GoogleDesktopExtensionBridgeResolver() {}

    static Binding resolve(ClassLoader loader) {
        if (loader == null) return new Binding(null, null, "classLoader=null");
        final Class<?> bridge;
        try {
            bridge = Class.forName(ACTIONS_BRIDGE, false, loader);
        } catch (Throwable t) {
            return new Binding(null, null, "ExtensionActionsBridge missing");
        }

        Method named = null;
        ArrayList<Method> structural = new ArrayList<>();
        for (Method method : allMethods(bridge)) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() != boolean.class || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (!looksLikeProfile(parameter)) continue;
            if ("extensionsEnabled".equals(method.getName())) named = method;
            structural.add(method);
        }

        Method selected = named;
        String detail;
        if (selected != null) {
            detail = "stable-name";
        } else if (structural.size() == 1) {
            selected = structural.get(0);
            detail = "unique-static-boolean-profile";
        } else if (structural.isEmpty()) {
            detail = "no-static-boolean-profile-method";
        } else {
            detail = "ambiguous-static-boolean-profile-methods=" + structural.size();
        }

        if (selected != null) {
            try { selected.setAccessible(true); } catch (Throwable ignored) {}
        }
        return new Binding(bridge, selected, detail);
    }

    private static List<Method> allMethods(Class<?> type) {
        ArrayList<Method> out = new ArrayList<>();
        try {
            for (Method method : type.getDeclaredMethods()) out.add(method);
        } catch (Throwable ignored) {}
        try {
            for (Method method : type.getMethods()) if (!out.contains(method)) out.add(method);
        } catch (Throwable ignored) {}
        return out;
    }

    private static boolean looksLikeProfile(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return "org.chromium.chrome.browser.profiles.Profile".equals(name)
                || name.endsWith(".Profile")
                || name.toLowerCase().contains("profile");
    }

    private static String describe(Method method) {
        if (method == null) return "missing";
        StringBuilder out = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(',');
            out.append(params[i].getName());
        }
        return out.append("):boolean").toString();
    }
}
