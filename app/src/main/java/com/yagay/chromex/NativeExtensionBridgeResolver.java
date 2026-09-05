package com.yagay.chromex;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-only bridge resolver for Chromium forks that already ship an extension runtime.
 * The resolver never assumes a vendor ABI: it binds only methods whose Java signatures are
 * compatible with a requested operation and catches every invocation failure.
 */
public final class NativeExtensionBridgeResolver {
    private static final String SYSTEM_MANAGER =
            "org.chromium.chrome.browser.extensions.ExtensionSystemManager";
    private static final String INSTALLER_BRIDGE =
            "org.chromium.chrome.browser.extensions.ExtensionInstallerBridge";
    private static final String ACTION_MANAGER =
            "org.chromium.chrome.browser.extensions.ExtensionActionManagerBridge";

    public static final class Binding {
        final ClassLoader classLoader;
        final Method listMethod;
        final Method installMethod;
        final Method uninstallMethod;
        final Method actionMethod;
        final List<String> diagnostics;

        Binding(ClassLoader classLoader, Method listMethod, Method installMethod,
                Method uninstallMethod, Method actionMethod, List<String> diagnostics) {
            this.classLoader = classLoader;
            this.listMethod = listMethod;
            this.installMethod = installMethod;
            this.uninstallMethod = uninstallMethod;
            this.actionMethod = actionMethod;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public boolean hasAnyCallableBridge() {
            return listMethod != null || installMethod != null || uninstallMethod != null
                    || actionMethod != null;
        }

        public String diagnosticsText() {
            StringBuilder out = new StringBuilder();
            for (String line : diagnostics) out.append(line).append('\n');
            return out.toString();
        }
    }

    private NativeExtensionBridgeResolver() {}

    public static Binding resolve(ClassLoader classLoader) {
        List<String> diag = new ArrayList<>();
        Class<?> system = load(classLoader, SYSTEM_MANAGER, diag);
        Class<?> installer = load(classLoader, INSTALLER_BRIDGE, diag);
        Class<?> action = load(classLoader, ACTION_MANAGER, diag);

        Method list = firstCompatible(system,
                new String[]{"getExtensions", "getAllExtensions", "getExtensionBeans"},
                new Class<?>[0], diag);
        Method install = firstOneArg(installer,
                new String[]{"silentInstallCrx", "installBackground"}, diag,
                File.class, String.class);
        Method uninstall = firstOneArg(installer,
                new String[]{"silentUninstallByID", "silentUninstallById", "uninstall"}, diag,
                String.class);
        Method execute = firstOneArg(action,
                new String[]{"executeAction", "browserAction"}, diag, String.class);

        return new Binding(classLoader, list, install, uninstall, execute, diag);
    }

    public static List<String> listExtensionIds(Binding binding) {
        if (binding == null || binding.listMethod == null) return Collections.emptyList();
        try {
            Object result = binding.listMethod.invoke(null);
            LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
            collectIds(result, ids, 0);
            return new ArrayList<>(ids.keySet());
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", "Extension list bridge failed :: " + shortError(t));
            return Collections.emptyList();
        }
    }

    public static boolean installCrx(Binding binding, File crx) {
        if (binding == null || binding.installMethod == null || crx == null || !crx.isFile()) {
            return false;
        }
        try {
            Class<?> p = binding.installMethod.getParameterTypes()[0];
            Object value = File.class.isAssignableFrom(p) ? crx : crx.getAbsolutePath();
            Object result = binding.installMethod.invoke(null, value);
            return successValue(result, binding.installMethod.getReturnType());
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", "Extension CRX bridge failed :: " + shortError(t));
            return false;
        }
    }

    public static boolean uninstall(Binding binding, String id) {
        return invokeString(binding == null ? null : binding.uninstallMethod, id,
                "Extension uninstall bridge failed");
    }

    public static boolean executeAction(Binding binding, String id) {
        return invokeString(binding == null ? null : binding.actionMethod, id,
                "Extension action bridge failed");
    }

    private static boolean invokeString(Method method, String value, String label) {
        if (method == null || value == null || value.isBlank()) return false;
        try {
            Object result = method.invoke(null, value);
            return successValue(result, method.getReturnType());
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", label + " :: " + shortError(t));
            return false;
        }
    }

    private static boolean successValue(Object result, Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return true;
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).longValue() >= 0L;
        return result != null;
    }

    private static Class<?> load(ClassLoader loader, String name, List<String> diag) {
        try {
            Class<?> c = Class.forName(name, false, loader);
            diag.add("CLASS + " + name);
            return c;
        } catch (Throwable t) {
            diag.add("CLASS - " + name);
            return null;
        }
    }

    private static Method firstCompatible(Class<?> owner, String[] names, Class<?>[] params,
            List<String> diag) {
        if (owner == null) return null;
        for (String name : names) {
            try {
                Method m = owner.getDeclaredMethod(name, params);
                if (!Modifier.isStatic(m.getModifiers())) {
                    diag.add("METHOD ! non-static " + signature(m));
                    continue;
                }
                m.setAccessible(true);
                diag.add("METHOD + " + signature(m));
                return m;
            } catch (Throwable ignored) {}
        }
        for (Method m : owner.getDeclaredMethods()) {
            for (String name : names) {
                if (!m.getName().equals(name) || m.getParameterCount() != params.length
                        || !Modifier.isStatic(m.getModifiers())) continue;
                boolean ok = true;
                Class<?>[] actual = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (!actual[i].isAssignableFrom(params[i])
                            && !params[i].isAssignableFrom(actual[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                diag.add("METHOD + " + signature(m));
                return m;
            }
        }
        diag.add("METHOD - " + owner.getName() + " " + String.join("/", names));
        return null;
    }

    private static Method firstOneArg(Class<?> owner, String[] names, List<String> diag,
            Class<?>... accepted) {
        if (owner == null) return null;
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() != 1 || !Modifier.isStatic(m.getModifiers())) continue;
            boolean nameMatch = false;
            for (String name : names) if (m.getName().equals(name)) nameMatch = true;
            if (!nameMatch) continue;
            Class<?> p = m.getParameterTypes()[0];
            boolean typeMatch = false;
            for (Class<?> a : accepted) {
                if (p == a || p.isAssignableFrom(a) || a.isAssignableFrom(p)) typeMatch = true;
            }
            if (!typeMatch) continue;
            try { m.setAccessible(true); } catch (Throwable ignored) {}
            diag.add("METHOD + " + signature(m));
            return m;
        }
        diag.add("METHOD - " + owner.getName() + " " + String.join("/", names));
        return null;
    }

    private static void collectIds(Object value, Map<String, Boolean> out, int depth) {
        if (value == null || depth > 4) return;
        if (value instanceof CharSequence) {
            String s = value.toString().trim();
            if (looksLikeExtensionId(s)) out.put(s, Boolean.TRUE);
            return;
        }
        if (value instanceof Map) {
            for (Object entryValue : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entryValue;
                collectIds(e.getKey(), out, depth + 1);
                collectIds(e.getValue(), out, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collectIds(item, out, depth + 1);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) collectIds(Array.get(value, i), out, depth + 1);
            return;
        }
        for (String getter : new String[]{"getId", "getExtensionId", "id", "extensionId"}) {
            try {
                Method m = type.getMethod(getter);
                if (m.getParameterCount() == 0) {
                    collectIds(m.invoke(value), out, depth + 1);
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                Field f = type.getDeclaredField(getter);
                f.setAccessible(true);
                collectIds(f.get(value), out, depth + 1);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private static boolean looksLikeExtensionId(String value) {
        if (value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'a' || c > 'p') return false;
        }
        return true;
    }

    private static String signature(Method m) {
        StringBuilder out = new StringBuilder(m.getDeclaringClass().getName())
                .append('#').append(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) out.append(',');
            out.append(p[i].getSimpleName());
        }
        return out.append(")->").append(m.getReturnType().getSimpleName()).toString();
    }

    private static String shortError(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
