package com.yagay.chromex;

import android.content.res.Resources;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Read-only, 158.0.8109.0-source-grounded discovery for installed 158.0.8110.0 DEX. */
final class Chromium158TabletProbe {
    private static final String PREFIX = "tablet-ui: source158: ";
    private static final String FORM = "org.chromium.ui.base.DeviceFormFactor";
    // These exact TraceEvent strings exist in the Chromium 158.0.8109.0 Java sources.
    private static final String[] ANCHORS = {
            "ChromeTabbedActivity.createLayoutManagerForTablet",
            "ChromeTabbedActivity.createLayoutManagerForPhone",
            "ToolbarControlContainer.initWithToolbar",
            "ChromeTabbedActivity.setupCompositorContent"
    };
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final ChromeRuntime runtime;
    private final HookSupport hooks;

    Chromium158TabletProbe(ChromeRuntime runtime, HookSupport hooks) {
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void startAsync() {
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                inspect();
            } catch (Throwable error) {
                hooks.warn(PREFIX + "scan aborted safely: "
                        + error.getClass().getSimpleName());
            } finally {
                RUNNING.set(false);
            }
        }, "ChromeX-Tablet158-Scan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void inspect() {
        hooks.info(PREFIX + "BEGIN package=" + runtime.packageName
                + " version=" + runtime.versionName
                + " codePaths=" + runtime.dexPaths().size());
        Resources res = runtime.application.getResources();
        for (String name : new String[]{"tab_strip_height",
                "tab_strip_reserved_top_padding", "control_container_height",
                "toolbar_height_no_shadow"}) {
            printResource(res, "dimen", name);
        }
        for (String name : new String[]{"toolbar_phone", "toolbar_tablet",
                "control_container"}) {
            printResource(res, "layout", name);
        }
        printDeviceFormFactorMethods();
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable error) {
            hooks.warn(PREFIX + "DexKit unavailable: "
                    + error.getClass().getSimpleName());
            return;
        }
        List<String> found = new ArrayList<>();
        for (String trace : ANCHORS) {
            Method method = findUniqueAnchor(trace);
            if (method == null) {
                hooks.warn(PREFIX + "UNRESOLVED " + trace);
                continue;
            }
            String symbol = method.getDeclaringClass().getName() + "#" + method.getName();
            ResolverCacheClient.put(runtime, "tablet158:trace:" + trace, symbol);
            hooks.info(PREFIX + "RESOLVED " + trace + " => " + method.toGenericString());
            found.add(trace);
        }
        // Identifying the method does NOT certify native compositor and resource equivalence.
        hooks.info(PREFIX + "DONE " + found.size() + "/" + ANCHORS.length
                + " source traces; diagnostic-only; no FormFactor/layout Hooks installed");
    }

    private void printResource(Resources res, String type, String name) {
        try {
            int id = res.getIdentifier(name, type, runtime.packageName);
            if (id == 0) {
                hooks.warn(PREFIX + "RESOURCE " + type + "/" + name
                        + " unresolved (possibly stripped resource names)");
                return;
            }
            String value = "dimen".equals(type)
                    ? " px=" + res.getDimensionPixelSize(id) : "";
            hooks.info(PREFIX + "RESOURCE " + type + "/" + name
                    + " id=0x" + Integer.toHexString(id) + value);
        } catch (Throwable e) {
            hooks.warn(PREFIX + "RESOURCE " + name + " failed: "
                    + e.getClass().getSimpleName());
        }
    }

    private void printDeviceFormFactorMethods() {
        try {
            Class<?> type = Class.forName(FORM, false, runtime.classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() > 1) continue;
                Class<?> out = method.getReturnType();
                if (out != int.class && out != boolean.class) continue;
                Class<?>[] args = method.getParameterTypes();
                String param = args.length == 0 ? "" : args[0].getName();
                hooks.info(PREFIX + "FORMFACTOR " + method.getName()
                        + "(" + param + "):" + out.getSimpleName());
            }
        } catch (Throwable error) {
            hooks.warn(PREFIX + "FORMFACTOR inaccessible: "
                    + error.getClass().getSimpleName());
        }
    }

    private Method findUniqueAnchor(String literal) {
        Method result = null;
        for (String path : runtime.dexPaths()) {
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                MethodData data = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(literal))).single();
                if (data == null) continue;
                Method method = data.getMethodInstance(runtime.classLoader);
                if (method == null) continue;
                if (result != null
                        && !result.toGenericString().equals(method.toGenericString())) {
                    hooks.warn(PREFIX + "AMBIGUOUS " + literal);
                    return null;
                }
                result = method;
            } catch (Throwable error) {
                hooks.info(PREFIX + "dex scan skipped: "
                        + error.getClass().getSimpleName());
            }
        }
        return result;
    }
}
