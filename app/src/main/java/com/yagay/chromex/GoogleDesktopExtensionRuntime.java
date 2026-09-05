package com.yagay.chromex;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime unlock for Chromium's official Desktop Android extension implementation.
 *
 * <p>The hook is installed only after the capability detector classified the build as
 * GOOGLE_DESKTOP_FULL and the resolver found an unambiguous static boolean(Profile) gate. Existing
 * true results are preserved; only false is promoted to true. No JNI method is called directly.</p>
 */
final class GoogleDesktopExtensionRuntime {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private GoogleDesktopExtensionRuntime() {}

    static boolean install(GoogleDesktopExtensionBackend backend, HookSupport hooks) {
        if (backend == null || hooks == null || !backend.canUnlock()) return false;
        if (!INSTALLED.compareAndSet(false, true)) return true;

        GoogleDesktopExtensionBridgeResolver.Binding binding = backend.binding();
        Method gate = binding == null ? null : binding.extensionsEnabled;
        if (gate == null) {
            INSTALLED.set(false);
            return false;
        }

        try {
            hooks.method(gate, "chromex:plus:google-desktop:extensions-enabled", chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result)) return result;
                RuntimeDiagnostics.event("INFO",
                        "Google Desktop Android extensionsEnabled(Profile) promoted false -> true via "
                                + gate.getDeclaringClass().getName() + '#' + gate.getName());
                return Boolean.TRUE;
            });
            hooks.info("Google Desktop Android extension gate attached: "
                    + gate.getDeclaringClass().getName() + '#' + gate.getName());
            return true;
        } catch (Throwable t) {
            INSTALLED.set(false);
            hooks.error("Google Desktop Android extension gate", t);
            return false;
        }
    }

    static void resetForHotReload() {
        INSTALLED.set(false);
    }
}
