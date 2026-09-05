package com.yagay.chromex;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Common extension backend contract for FULL and LITE runtimes. */
public interface ExtensionBackend {
    ExtensionRuntimeMode mode();

    boolean isAvailable();

    default List<String> getInstalledExtensionIds() {
        return Collections.emptyList();
    }

    default boolean installCrx(File crx) {
        return false;
    }

    default boolean uninstall(String extensionId) {
        return false;
    }

    default boolean setEnabled(String extensionId, boolean enabled) {
        return false;
    }

    default boolean executeAction(String extensionId) {
        return false;
    }

    default String diagnostics() {
        return mode().name();
    }
}
