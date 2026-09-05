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

    /**
     * @return true only when the modern path explicitly disabled saving to TabRestoreService.
     *         false means callers may still need the legacy recently-closed cleanup fallback.
     */
    private static boolean close(ClassLoader loader, Object model, List<Object> tabs,
                                 HookSupport hooks, boolean uponExit) {
        if (tabs == null || tabs.isEmpty()) return true;
        try {
            Object remover = Reflect.call(model, "getTabRemover");
            if (remover == null) {
                fallback(loader, model, tabs);
                return false;
            }

            Class<?> params = Reflect.cls(loader, TAB_CLOSURE_PARAMS);
            Method factory = closeTabsFactory(params);
            if (factory == null && tabs.size() == 1) {
                Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
                try { factory = Reflect.exact(params, "closeTab", tabType); }
                catch (Throwable ignored) {}
            }
            if (factory == null) {
                fallback(loader, model, tabs);
                return false;
            }

            boolean listFactory = List.class.isAssignableFrom(factory.getParameterTypes()[0]);
            Object builder = factory.invoke(null, listFactory ? tabs : tabs.get(0));
            if (builder == null) {
                fallback(loader, model, tabs);
                return false;
            }

            builder = requiredBuilder(builder, "allowUndo", false);
            boolean restoreSuppressed = false;
            try {
                Object next = Reflect.call(builder, "saveToTabRestoreService", false);
                builder = next == null ? builder : next;
                restoreSuppressed = true;
            } catch (Throwable ignored) {}
            try {
                Object next = Reflect.call(builder, "uponExit", uponExit);
                builder = next == null ? builder : next;
            } catch (Throwable ignored) {}

            Object built = Reflect.call(builder, "build");
            if (built == null) {
                fallback(loader, model, tabs);
                return false;
            }

            try { Reflect.call(remover, "forceCloseTabs", built); }
            catch (Throwable forceFailure) { Reflect.call(remover, "closeTabs", built, Boolean.FALSE); }

            if (hooks != null) hooks.info("automatic tabs closed through TabClosureParams; count="
                    + tabs.size() + " restoreSuppressed=" + restoreSuppressed
                    + " uponExit=" + uponExit);
            return restoreSuppressed;
        } catch (Throwable t) {
            if (hooks != null) hooks.warn("TabClosureParams cleanup unavailable: "
                    + t.getClass().getSimpleName());
            fallback(loader, model, tabs);
            return false;
        }
    }

    private static Method closeTabsFactory(Class<?> params) {
        for (Method method : params.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || !"closeTabs".equals(method.getName())
                    || method.getParameterCount() != 1
                    || !List.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
            try { method.setAccessible(true); } catch (Throwable ignored) {}
            return method;
        }
        return null;
    }

    private static Object requiredBuilder(Object builder, String name, boolean value) throws Exception {
        Object next = Reflect.call(builder, name, value);
        return next == null ? builder : next;
    }

    private static void fallback(ClassLoader loader, Object model, List<Object> tabs) {
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Object tab = tabs.get(i);
            boolean closed = false;
            try {
                Reflect.call(model, "closeTab", tab,
                        Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);
                closed = true;
            } catch (Throwable ignored) {}
            if (!closed) {
                try {
                    Reflect.call(model, "closeTab", tab);
                    closed = true;
                } catch (Throwable ignored) {}
            }
            if (!closed) {
                try {
                    Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
                    Method method = Reflect.exact(model.getClass(), "closeTab", tabType);
                    method.invoke(model, tab);
                } catch (Throwable ignored) {}
            }
        }
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
