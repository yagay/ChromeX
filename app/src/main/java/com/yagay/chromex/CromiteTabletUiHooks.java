package com.yagay.chromex;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Source-guided adaptation of Cromite's "Add option to force tablet UI" patch.
 *
 * <p>Uses Chromium's own tablet tab strip. This is opt-in and fails closed if upstream class
 * names/signatures change. Ordinary LSPosed API 102 does not provide XML resource hooks; even
 * successfully installed Java hooks do NOT prove the tablet compositor is visible on the phone.
 */
final class CromiteTabletUiHooks {
    private static final String PREFIX = "chromex:cromite-tablet:";
    private static final String DEVICE_FORM_FACTOR = "org.chromium.ui.base.DeviceFormFactor";
    private static final int TABLET_BUCKET = 2;  // Chromium DeviceFormFactor.SCREEN_BUCKET_TABLET.
    private static final int STRIP_HEIGHT_DP = 40;
    private static final int RESERVED_PADDING_DP = 2;

    private final ChromeRuntime runtime;
    private final HookSupport hooks;

    CromiteTabletUiHooks(ChromeRuntime runtime, HookSupport hooks) {
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void install() {
        // Never hook the Android framework, system_server, or a browser where Chromium's
        // DeviceFormFactor cannot be resolved. This setting is an explicit opt-in.
        Class<?> formFactor = find(DEVICE_FORM_FACTOR);
        if (formFactor == null) {
            hooks.warn("tablet-ui: DeviceFormFactor not available; no tablet hooks installed"
                    + " (split dex/obfuscation may differ)");
            return;
        }
        int base = installFormFactor(formFactor);
        if (base == 0) {
            hooks.warn("tablet-ui: no compatible DeviceFormFactor methods; feature inactive");
            return;
        }
        installThreshold();
        installStripHeight();
        installStripLayoutInitialization();
        installReservedTopPadding();
        installToolbarPlacement();
        installFindToolbarFallback();
        hooks.info("tablet-ui: installed Chromium source-compatible hooks, coreMethods=" + base
                + "; NOT YET VERIFIED that target APK supplies the tablet layout and native strip."
                + " Cromite also changes XML resources and compositor positioning.");
    }

    private int installFormFactor(Class<?> type) {
        int count = 0;
        count += replaceMethods(type, "isTablet", boolean.class, 0, true);
        count += replaceMethods(type, "isNonMultiDisplayContextOnTablet", boolean.class, 1, true);
        count += replaceMethods(type, "isWindowOnTablet", boolean.class, 1, true);
        // Two overloads accept Context or WindowAndroid. Do not alter the Android system
        // configuration or pretend that physical display dimensions changed.
        count += replaceMethods(type, "detectScreenWidthBucket", int.class, 1, TABLET_BUCKET);
        count += replaceMethods(type, "getNonMultiDisplayMinimumTabletWidthPx", int.class, 1, 0);
        count += replaceMethods(type, "getMinimumTabletWidthPx", int.class, 1, 0);
        return count;
    }

    private void installThreshold() {
        Class<?> type = find("org.chromium.chrome.browser.toolbar.top.tab_strip.HeightTransitionHandler");
        if (type != null) replaceMethods(type, "getScreenWidthThresholdDp", int.class, 0, 0);
    }

    private void installStripHeight() {
        Class<?> type = find("org.chromium.chrome.browser.toolbar.top.ToolbarLayout");
        if (type == null) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!matches(method, "getTabStripHeightFromResource", int.class, 0)) continue;
            hooks.method(method, PREFIX + "strip-height", chain -> {
                Object target = chain.getThisObject();
                if (target instanceof View) {
                    return dp((View) target, STRIP_HEIGHT_DP);
                }
                return chain.proceed();
            });
        }
    }

    private void installStripLayoutInitialization() {
        Class<?> type = find("org.chromium.chrome.browser.compositor.overlays.strip.StripLayoutHelperManager");
        if (type == null) return;
        // On phones the original resource-backed strip height is 0. Cromite initializes
        // mScrollableStripHeight from a 40dp replacement resource, irrespective of -sw600dp.
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            hooks.constructor(ctor, PREFIX + "strip-ctor-" + ctor.getParameterCount(), chain -> {
                Object value = chain.proceed();
                Object target = chain.getThisObject();
                if (target != null) {
                    setFloatIfPresent(target, "mScrollableStripHeight", STRIP_HEIGHT_DP);
                }
                return value;
            });
        }
    }

    private void installReservedTopPadding() {
        Class<?> type = find("org.chromium.chrome.browser.toolbar.top.tab_strip.TabStripTransitionCoordinator");
        if (type == null) return;
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            hooks.constructor(ctor, PREFIX + "transition-ctor-" + ctor.getParameterCount(),
                    chain -> {
                        Object value = chain.proceed();
                        Object target = chain.getThisObject();
                        if (target != null) {
                            Resources res = runtime.application.getResources();
                            int px = Math.round(RESERVED_PADDING_DP
                                    * res.getDisplayMetrics().density);
                            setIntIfPresent(target, "mTabStripReservedTopPadding", px);
                        }
                        return value;
                    });
        }
    }

    private void installToolbarPlacement() {
        Class<?> type = find("org.chromium.chrome.browser.toolbar.top.ToolbarControlContainer");
        if (type == null) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!matches(method, "initWithToolbar", void.class, 2)) continue;
            Class<?>[] args = method.getParameterTypes();
            if (args[0] != int.class || args[1] != int.class) continue;
            hooks.method(method, PREFIX + "toolbar-placement", chain -> {
                Object result = chain.proceed();
                Object container = chain.getThisObject();
                if (container instanceof ViewGroup) {
                    adjustToolbarAfterInflation((ViewGroup) container);
                }
                return result;
            });
        }
    }

    private void installFindToolbarFallback() {
        Class<?> type = find("org.chromium.chrome.browser.ui.RootUiCoordinator");
        if (type == null) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!matches(method, "getFindToolbarStub", int.class, 0)) continue;
            hooks.method(method, PREFIX + "find-toolbar-stub", chain -> {
                int phoneStub = resourceId(runtime.application.getResources(), "find_toolbar_stub",
                        "id");
                return phoneStub != 0 ? phoneStub : chain.proceed();
            });
        }
    }

    private void adjustToolbarAfterInflation(ViewGroup root) {
        // This is a View-only workaround for the missing "toolbar_tablet.xml" 40dp top
        // margin on phone-qualified resources. Do not globally modify resource density.
        int toolbarId = resourceId(root.getResources(), "toolbar", "id");
        if (toolbarId == 0) return;
        View toolbar = root.findViewById(toolbarId);
        if (toolbar == null) {
            hooks.warn("tablet-ui: toolbar View not inflated; cannot correct tablet margin");
            return;
        }
        ViewGroup.LayoutParams raw = toolbar.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        int intended = dp(root, STRIP_HEIGHT_DP);
        if (lp.topMargin >= intended) return;
        lp.topMargin = intended;
        toolbar.setLayoutParams(lp);
        root.requestLayout();
    }

    private int resourceId(Resources resources, String name, String kind) {
        return resources.getIdentifier(name, kind, runtime.packageName);
    }

    private static int dp(View view, int dimension) {
        return Math.round(dimension * view.getResources().getDisplayMetrics().density);
    }

    private static Field field(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field found = current.getDeclaredField(fieldName);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private void setFloatIfPresent(Object instance, String fieldName, float value) {
        try {
            Field target = field(instance.getClass(), fieldName);
            if (target != null && target.getType() == float.class) {
                target.setFloat(instance, value);
            } else {
                hooks.warn("tablet-ui: missing float field " + fieldName);
            }
        } catch (Throwable e) {
            hooks.warn("tablet-ui: could not update " + fieldName + " : "
                    + e.getClass().getSimpleName());
        }
    }

    private void setIntIfPresent(Object instance, String fieldName, int value) {
        try {
            Field target = field(instance.getClass(), fieldName);
            if (target != null && target.getType() == int.class) {
                target.setInt(instance, value);
            } else {
                hooks.warn("tablet-ui: missing int field " + fieldName);
            }
        } catch (Throwable e) {
            hooks.warn("tablet-ui: could not update " + fieldName + " : "
                    + e.getClass().getSimpleName());
        }
    }

    private int replaceMethods(Class<?> type, String name, Class<?> returns,
                               int arguments, Object value) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!matches(method, name, returns, arguments)) continue;
            final int slot = count++;
            hooks.method(method, PREFIX + name + "-" + slot, chain -> value);
        }
        if (count == 0) {
            hooks.warn("tablet-ui: no compatible " + type.getSimpleName() + "#" + name);
        }
        return count;
    }

    private static boolean matches(Method method, String name, Class<?> result, int args) {
        return method.getName().equals(name)
                && method.getReturnType() == result
                && method.getParameterCount() == args
                && !Modifier.isAbstract(method.getModifiers());
    }

    private Class<?> find(String name) {
        try {
            return Class.forName(name, false, runtime.classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            hooks.warn("tablet-ui: target class unavailable " + name);
            return null;
        }
    }
}
