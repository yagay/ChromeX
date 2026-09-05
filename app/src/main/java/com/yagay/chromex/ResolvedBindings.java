package com.yagay.chromex;

import java.lang.reflect.Method;

/**
 * Typed runtime bindings discovered once for one Chromium process.
 *
 * <p>Feature engines consume these resolved methods directly instead of repeating DexKit scans or
 * independently guessing R8 symbols. BrowserCapabilities is the human-readable projection of the
 * same resolution pass.</p>
 */
final class ResolvedBindings {
    final BrowserCapabilities capabilities;

    final Method homepageGetter;
    final Method tabCreator;
    final Method newTabSource;
    final Method tabStateReady;
    final Method recentlyClosedClear;

    final Method offlineItemMaterializer;
    final Method offlineItemsAdded;
    final Method offlineItemUpdated;
    final Method offlineContentOpenItem;
    final Method downloadPromptGetter;

    ResolvedBindings(BrowserCapabilities capabilities,
                     Method homepageGetter,
                     Method tabCreator,
                     Method newTabSource,
                     Method tabStateReady,
                     Method recentlyClosedClear,
                     Method offlineItemMaterializer,
                     Method offlineItemsAdded,
                     Method offlineItemUpdated,
                     Method offlineContentOpenItem,
                     Method downloadPromptGetter) {
        this.capabilities = capabilities;
        this.homepageGetter = accessible(homepageGetter);
        this.tabCreator = accessible(tabCreator);
        this.newTabSource = accessible(newTabSource);
        this.tabStateReady = accessible(tabStateReady);
        this.recentlyClosedClear = accessible(recentlyClosedClear);
        this.offlineItemMaterializer = accessible(offlineItemMaterializer);
        this.offlineItemsAdded = accessible(offlineItemsAdded);
        this.offlineItemUpdated = accessible(offlineItemUpdated);
        this.offlineContentOpenItem = accessible(offlineContentOpenItem);
        this.downloadPromptGetter = accessible(downloadPromptGetter);
    }

    private static Method accessible(Method method) {
        if (method == null) return null;
        try { method.setAccessible(true); } catch (Throwable ignored) {}
        return method;
    }
}
