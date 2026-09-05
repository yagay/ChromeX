package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Uses Chromium's own non-undoable/non-restorable tab closure path when available. */
final class TabCloseStrategy {
    private static final String TAB_CLOSURE_PARAMS =
            "org.chromium.chrome.browser.tabmodel.TabClosureParams";

    private TabCloseStrategy() {}

    static boolean closeAll(ClassLoader loader, Object model, HookSupport hooks, boolean uponExit) {
        if (model == null) return true;
        ArrayList<Object> tabs = new ArrayList<>();
        int count = count(model);
        for (int i = 0; i < count; i++) {
            Object tab = tabAt(model, i);
            if (tab != null) tabs.add(tab);
        }
        return close(loader, model, tabs, hooks, uponExit);
    }

    static boolean closeExcept(ClassLoader loader, Object model, Object keep,
                               HookSupport hooks, boolean uponExit) {
        if (model == null) return true;
        ArrayList<Object> tabs = new ArrayList<>();
        int count = count(model);
        for (int i = 0; i < count; i++) {
            Object tab = tabAt(model, i);
            if (tab != null && tab != keep) tabs.add(tab);
        }
        return close(loader, model, tabs, hooks, uponExit);
    }

    private static boolean close(ClassLoader loader, Object model, List<Object> tabs,
                                 HookSupport hooks, boolean uponExit) {
        if (tabs == null || tabs.isEmpty()) return true;
        try {
            Object remover = Reflect.call(model, "getTabRemover");
            if (remover == null) return fallback(model, tabs);

            Class<?> params = Reflect.cls(loader, TAB_CLOSURE_PARAMS);
            Method factory = null;
            for (Method method : params.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || !"closeTabs".equals(method.getName())
                        || method.getParameterCount() != 1
                        || !List.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                method.setAccessible(true);
                factory = method;
                break;
            }
            if (factory == null && tabs.size() == 1) {
                Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
                try { factory = Reflect.exact(params, "closeTab", tabType); }
                catch (Throwable ignored) {}
            }
            if (factory == null) return fallback(model, tabs);

            Object builder = factory.invoke(null, tabs.size() == 1
                    && !List.class.isAssignableFrom(factory.getParameterTypes()[0])
                    ? tabs.get(0) : tabs);
            if (builder == null) return fallback(model, tabs);
            builder = callBuilder(builder, "allowUndo", false);
            builder = callBuilderIfPresent(builder, "saveToTabRestoreService", false);
            builder = callBuilderIfPresent(builder, "uponExit", uponExit);
            Object built = Reflect.call(builder, "build");
            if (built == null) return fallback(model, tabs);

            try {
                Reflect.call(remover, "forceCloseTabs", built);
            } catch (Throwable forceFailure) {
                Reflect.call(remover, "closeTabs", built, Boolean.FALSE);
            }
            if (hooks != null) hooks.info("automatic tabs closed through TabClosureParams; count="
                    + tabs.size() + " uponExit=" + uponExit);
            return true;
        } catch (Throwable t) {
            if (hooks != null) hooks.warn("TabClosureParams cleanup unavailable: "
                    + t.getClass().getSimpleName());
            return fallback(model, tabs);
        }
    }

    private static Object callBuilder(Object builder, String name, boolean value) throws Exception {
        Object next = Reflect.call(builder, name, value);
        return next == null ? builder : next;
    }

    private static Object callBuilderIfPresent(Object builder, String name, boolean value) {
        try {
            Object next = Reflect.call(builder, name, value);
            return next == null ? builder : next;
        } catch (Throwable ignored) {
            return builder;
        }
    }

    private static boolean fallback(Object model, List<Object> tabs) {
        boolean success = true;
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Object tab = tabs.get(i);
            try {
                Class<?> tabType = tab.getClass();
                Method close = Reflect.exact(model.getClass(), "closeTab", tabType);
                close.invoke(model, tab);
            } catch (Throwable first) {
                try { Reflect.call(model, "closeTab", tab); }
                catch (Throwable ignored) { success = false; }
            }
        }
        return false && success;
    }

    private static int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private static Object tabAt(Object model, int index) {
        try { return Reflect.call(model, "getTabAt", index); }
        catch (Throwable ignored) { return null; }
    }
}
