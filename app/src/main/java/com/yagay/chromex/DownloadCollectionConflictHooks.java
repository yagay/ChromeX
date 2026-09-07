package com.yagay.chromex;

import android.content.SharedPreferences;

/**
 * Bypasses Chromium's Java-side MediaStore/DownloadCollection duplicate-name probe.
 *
 * <p>Chrome 152.0.7977.75 exposes
 * org.chromium.components.download.DownloadCollectionBridge.fileNameExists(String):boolean.
 * Its implementation is equivalent to querying the download collection for the display name and
 * returning true when a Uri is found. When same-name overwrite is enabled we deliberately report
 * false here so Chromium's later reservation stage is not forced back into UNIQUIFY merely because
 * MediaStore still contains the old display name.</p>
 */
final class DownloadCollectionConflictHooks {
    private static final String BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    DownloadCollectionConflictHooks(ChromeRuntime runtime, HookSupport hooks,
                                    SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Class<?> bridge = Reflect.cls(runtime.classLoader, BRIDGE);
            if (Reflect.named(bridge, "fileNameExists").isEmpty()) {
                hooks.warn("DownloadCollectionBridge.fileNameExists unresolved");
                return;
            }
        } catch (Throwable t) {
            hooks.warn("DownloadCollectionBridge unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, BRIDGE, "fileNameExists",
                "chromex:download-collection:file-name-exists", chain -> {
                    if (!Config.get(prefs, Config.OVERWRITE_DUPLICATE)) {
                        return chain.proceed();
                    }
                    String name = null;
                    try {
                        Object arg = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                        if (arg instanceof String) name = (String) arg;
                    } catch (Throwable ignored) {}
                    hooks.info("ChromeX DownloadCollection duplicate bypass: "
                            + (name == null ? "<unknown>" : name));
                    return false;
                });

        hooks.info("ChromeX DownloadCollectionBridge.fileNameExists hook installed");
    }
}
