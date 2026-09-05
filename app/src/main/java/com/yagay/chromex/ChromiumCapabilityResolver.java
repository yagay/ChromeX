package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Resolves semantic Chromium capabilities and typed bindings in one pass. */
final class ChromiumCapabilityResolver {
    private static final String COMMAND_LINE = "org.chromium.base.CommandLine";
    private static final String PREF_SERVICE = "org.chromium.components.prefs.PrefService";
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String DOWNLOAD_DIALOG =
            "org.chromium.chrome.browser.download.DownloadDialogBridge";
    private static final String TAB_CREATOR_UTIL =
            "org.chromium.chrome.browser.tabmodel.TabCreatorUtil";
    private static final String TAB_SELECTOR_BASE =
            "org.chromium.chrome.browser.tabmodel.TabModelSelectorBase";
    private static final String RECENTLY_CLOSED =
            "org.chromium.chrome.browser.ntp.RecentlyClosedBridge";

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final ClassLoader loader;

    private Method homepageGetter;
    private Method tabCreator;
    private Method newTabSource;
    private Method tabStateReady;
    private Method recentlyClosedClear;
    private Method offlineItemMaterializer;
    private Method offlineItemsAdded;
    private Method offlineItemUpdated;
    private Method offlineContentOpenItem;
    private Method downloadPromptGetter;

    ChromiumCapabilityResolver(ChromiumProfile profile, ChromeRuntime runtime, HookSupport hooks) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.loader = runtime.classLoader;
    }

    BrowserCapabilities resolve() {
        return resolveBindings().capabilities;
    }

    ResolvedBindings resolveBindings() {
        BrowserCapabilities.Builder out = BrowserCapabilities.builder();
        resolveCore(out);
        resolveTabs(out);
        resolveDownloads(out);
        resolveMisc(out);
        BrowserCapabilities capabilities = out.build();
        hooks.info("Chromium capabilities resolved: package=" + runtime.packageName
                + " engine=" + profile.engineVersion + " :: " + capabilities.summary());
        RuntimeDiagnostics.event("CAPABILITY", "package=" + runtime.packageName
                + " engine=" + profile.engineVersion + "\n" + capabilities.humanReport());
        return new ResolvedBindings(capabilities,
                homepageGetter, tabCreator, newTabSource, tabStateReady, recentlyClosedClear,
                offlineItemMaterializer, offlineItemsAdded, offlineItemUpdated,
                offlineContentOpenItem, downloadPromptGetter);
    }

    private void resolveCore(BrowserCapabilities.Builder out) {
        if (ChromiumEngineVersionScanner.plausible(profile.engineVersion)) {
            out.available(BrowserCapabilities.Key.ENGINE_VERSION,
                    BrowserCapabilities.Source.STABLE_API, 98,
                    "engine=" + profile.engineVersion);
        } else {
            out.unavailable(BrowserCapabilities.Key.ENGINE_VERSION, "engine version unresolved");
        }

        boolean activity = hasClass(Chrome145.ACTIVITY);
        if (activity) {
            out.available(BrowserCapabilities.Key.TABBED_ACTIVITY,
                    sourceForStable(), confidenceForStable(), Chrome145.ACTIVITY);
        } else {
            out.unavailable(BrowserCapabilities.Key.TABBED_ACTIVITY,
                    "stable activity absent; non-tab features may still work");
        }

        boolean gurl = hasClass(Chrome145.GURL);
        putType(out, BrowserCapabilities.Key.GURL, gurl, Chrome145.GURL);
        boolean profileType = hasClass(Chrome145.PROFILE) || hasClass(Chrome145.PROFILE_MANAGER);
        putType(out, BrowserCapabilities.Key.PROFILE, profileType, Chrome145.PROFILE);
        boolean pref = hasClass(PREF_SERVICE);
        putType(out, BrowserCapabilities.Key.PREF_SERVICE, pref, PREF_SERVICE);

        int score = 0;
        if (activity) score += 3;
        if (gurl) score++;
        if (profileType) score++;
        if (hasClass(Chrome145.WEB_CONTENTS)) score++;
        if (hasClass(Chrome145.DOWNLOAD_INFO)) score++;
        if (hasClass(Chrome145.TAB_MODEL_API)) score++;
        if (score >= 4) {
            out.available(BrowserCapabilities.Key.CORE_RUNTIME,
                    activity ? sourceForStable() : BrowserCapabilities.Source.STRUCTURAL,
                    activity ? confidenceForStable() : 80,
                    "chromium-anchor-score=" + score + " codePaths=" + runtime.dexPaths().size());
        } else {
            out.unavailable(BrowserCapabilities.Key.CORE_RUNTIME,
                    "chromium-anchor-score=" + score);
        }
    }

    private void resolveTabs(BrowserCapabilities.Builder out) {
        boolean tabModel = hasClass(Chrome145.TAB_MODEL_API)
                && (hasClass(Chrome145.TAB_MODEL) || hasClass(Chrome145.ACTIVITY));
        if (tabModel) {
            out.available(BrowserCapabilities.Key.TAB_MODEL,
                    hasClass(Chrome145.TAB_MODEL) ? sourceForStable()
                            : BrowserCapabilities.Source.STRUCTURAL,
                    hasClass(Chrome145.TAB_MODEL) ? confidenceForStable() : 80,
                    "TabModel API present");
        } else {
            out.unavailable(BrowserCapabilities.Key.TAB_MODEL, "TabModel API absent");
        }

        if (hasClass(Chrome145.CHROME_TAB_CREATOR)) {
            out.available(BrowserCapabilities.Key.TAB_CREATOR,
                    sourceForStable(), confidenceForStable(), "ChromeTabCreator stable class");
        } else {
            tabCreator = AdaptiveDexResolver.resolveTabCreator(runtime, hooks);
            if (tabCreator != null) {
                out.available(BrowserCapabilities.Key.TAB_CREATOR,
                        BrowserCapabilities.Source.SEMANTIC_DEX, 95,
                        describe(tabCreator));
            } else if (hasClass(ChromiumSemanticAnchors.LOAD_URL_PARAMS)
                    && hasClass(ChromiumSemanticAnchors.TAB) && tabModel) {
                out.available(BrowserCapabilities.Key.TAB_CREATOR,
                        BrowserCapabilities.Source.LIVE_RUNTIME, 72,
                        "resolve LoadUrlParams -> Tab creator from live graph");
            } else {
                out.unavailable(BrowserCapabilities.Key.TAB_CREATOR, "creator anchors absent");
            }
        }

        newTabSource = resolveNewTabSource();
        if (newTabSource != null) {
            out.available(BrowserCapabilities.Key.NEW_TAB_SOURCE,
                    BrowserCapabilities.Source.STABLE_API, 98,
                    describe(newTabSource) + " -> TabCreator.launchUrl");
        } else {
            out.unavailable(BrowserCapabilities.Key.NEW_TAB_SOURCE,
                    "TabCreatorUtil source decision unavailable; creator rewriting fallback");
        }

        if (profile.isVerifiedExact() && (hasClass(Chrome145.HOMEPAGE_MANAGER)
                || hasClass(profile.is145() ? Chrome145.HOMEPAGE : Chrome152.HOMEPAGE))) {
            out.available(BrowserCapabilities.Key.HOMEPAGE,
                    BrowserCapabilities.Source.VERIFIED_EXACT, 100, profile.label());
        } else {
            homepageGetter = AdaptiveDexResolver.resolveHomepageGetter(runtime, hooks);
            if (homepageGetter != null) {
                out.available(BrowserCapabilities.Key.HOMEPAGE,
                        BrowserCapabilities.Source.SEMANTIC_DEX, 95, describe(homepageGetter));
            } else if (hasClass(PREF_SERVICE)) {
                out.available(BrowserCapabilities.Key.HOMEPAGE,
                        BrowserCapabilities.Source.STRUCTURAL, 65,
                        "PrefService fallback available");
            } else {
                out.unavailable(BrowserCapabilities.Key.HOMEPAGE, "homepage binding unresolved");
            }
        }

        tabStateReady = method(TAB_SELECTOR_BASE, "markTabStateInitialized", 0);
        if (tabStateReady != null) {
            out.available(BrowserCapabilities.Key.TAB_STATE_READY,
                    BrowserCapabilities.Source.STABLE_API, 98, describe(tabStateReady));
        } else {
            out.unavailable(BrowserCapabilities.Key.TAB_STATE_READY,
                    "restore-ready event unavailable; lifecycle timing fallback");
        }

        recentlyClosedClear = method(RECENTLY_CLOSED, "clearRecentlyClosedEntries", 0);
        if (recentlyClosedClear != null) {
            out.available(BrowserCapabilities.Key.RECENTLY_CLOSED,
                    BrowserCapabilities.Source.STABLE_API, 98, describe(recentlyClosedClear));
        } else {
            out.unavailable(BrowserCapabilities.Key.RECENTLY_CLOSED,
                    "RecentlyClosedBridge clear unavailable");
        }

        if (profile.is145() && hasClass(Chrome145.COMMAND_FLAGS)) {
            out.available(BrowserCapabilities.Key.RESTORE_CONTROL,
                    BrowserCapabilities.Source.VERIFIED_EXACT, 100, Chrome145.COMMAND_FLAGS);
        } else if (hasBooleanStringMethod(COMMAND_LINE)) {
            out.available(BrowserCapabilities.Key.RESTORE_CONTROL,
                    sourceForStable(), confidenceForStable(), "CommandLine boolean(String)");
        } else {
            out.unavailable(BrowserCapabilities.Key.RESTORE_CONTROL,
                    "no safe no-restore command binding");
        }
    }

    private void resolveDownloads(BrowserCapabilities.Builder out) {
        boolean info = hasClass(Chrome145.DOWNLOAD_INFO);
        if (info) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_INFO,
                    BrowserCapabilities.Source.STRUCTURAL, profile.isVerifiedExact() ? 100 : 94,
                    "stable DownloadInfo type + value-shape accessor");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_INFO, "DownloadInfo absent");
        }

        offlineItemMaterializer = DownloadOfflineItemBinding.resolve(loader);
        if (offlineItemMaterializer != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_UI,
                    "createOfflineItem".equals(offlineItemMaterializer.getName()) ? sourceForStable()
                            : BrowserCapabilities.Source.STRUCTURAL,
                    "createOfflineItem".equals(offlineItemMaterializer.getName())
                            ? confidenceForStable() : 92,
                    describe(offlineItemMaterializer) + "(DownloadItem)->OfflineItem");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_UI,
                    "OfflineItem materializer unresolved");
        }

        resolveOfflineContentBindings();
        if (offlineItemUpdated != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_LIFECYCLE,
                    BrowserCapabilities.Source.STABLE_API, 96,
                    describe(offlineItemUpdated) + " state transition source");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_LIFECYCLE,
                    "OfflineContentAggregatorBridge update callback absent");
        }

        String completion = firstOwnerWithMethod("onDownloadCompleted",
                Chrome145.DOWNLOAD_CONTROLLER, Chrome145.DOWNLOAD_MANAGER_SERVICE);
        if (completion != null && info) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_COMPLETION,
                    sourceForStable(), confidenceForStable(), completion + "#onDownloadCompleted(*)");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_COMPLETION,
                    "legacy completion callback unresolved; OfflineContent lifecycle may still work");
        }

        if (hasMethod(DUPLICATE_BRIDGE, "showDialog")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_DUPLICATE_CONFLICT,
                    sourceForStable(), confidenceForStable(), DUPLICATE_BRIDGE + "#showDialog");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_DUPLICATE_CONFLICT,
                    "duplicate bridge absent");
        }

        boolean history = hasMethod(Chrome145.DOWNLOAD_MANAGER_SERVICE, "onDownloadItemUpdated")
                || hasMethod(Chrome145.DOWNLOAD_MANAGER_SERVICE, "onAllDownloadsRetrieved")
                || hasMethod(Chrome145.DOWNLOAD_MANAGER_SERVICE, "onDownloadItemCreated");
        if (history) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_HISTORY,
                    sourceForStable(), confidenceForStable(), "DownloadManagerService callbacks");
        } else if (offlineItemUpdated != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_HISTORY,
                    BrowserCapabilities.Source.STABLE_API, 88,
                    "OfflineContent observer is authoritative UI source");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_HISTORY,
                    "standard history callbacks absent; vendor/new backend possible");
        }

        if (hasClass(ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE)
                && hasClass("org.chromium.base.Callback") && offlineItemMaterializer != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_RENAME,
                    BrowserCapabilities.Source.STRUCTURAL, 90,
                    "OfflineContentAggregatorBridge source-of-truth rename candidate");
        } else if (hasMethod(Chrome145.DOWNLOAD_MANAGER_SERVICE, "renameDownload")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_RENAME,
                    sourceForStable(), confidenceForStable(),
                    "legacy DownloadManagerService#renameDownload record backend");
        } else if (hasMethod(ChromiumSemanticAnchors.DOWNLOAD_COLLECTION_BRIDGE,
                "renameDownloadUri")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_RENAME,
                    BrowserCapabilities.Source.STABLE_API, 72,
                    "modern DownloadCollectionBridge#renameDownloadUri MediaStore backend");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_RENAME,
                    "rename backend unresolved; transactional file fallback only");
        }

        if (offlineContentOpenItem != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OPEN,
                    BrowserCapabilities.Source.STABLE_API, 96,
                    describe(offlineContentOpenItem) + " source open");
        } else if (hasMethod(Chrome145.DOWNLOAD_UTILS, "openDownload")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OPEN,
                    sourceForStable(), confidenceForStable(), "DownloadUtils#openDownload");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_OPEN, "open backend absent");
        }

        downloadPromptGetter = method(DOWNLOAD_DIALOG, "getPromptForDownloadAndroid", 1);
        if (downloadPromptGetter != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_LOCATION_POLICY,
                    BrowserCapabilities.Source.STABLE_API, 98, describe(downloadPromptGetter));
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_LOCATION_POLICY,
                    "prompt preference source unavailable; dialog callback fallback");
        }

        if (hasMethod(DOWNLOAD_DIALOG, "showDialog")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_LOCATION_DIALOG,
                    sourceForStable(), confidenceForStable(), DOWNLOAD_DIALOG + "#showDialog");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_LOCATION_DIALOG,
                    "download location dialog absent");
        }
    }

    private void resolveOfflineContentBindings() {
        String owner = ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE;
        offlineItemsAdded = method(owner, "onItemsAdded", 1);
        offlineItemUpdated = method(owner, "onItemUpdated", 2);
        offlineContentOpenItem = method(owner, "openItem", 2);
    }

    private void resolveMisc(BrowserCapabilities.Builder out) {
        if (hasClass(Chrome145.TRANSLATE_MESSAGE)) {
            out.available(BrowserCapabilities.Key.TRANSLATE_MESSAGE,
                    sourceForStable(), confidenceForStable(), Chrome145.TRANSLATE_MESSAGE);
        } else {
            out.unavailable(BrowserCapabilities.Key.TRANSLATE_MESSAGE, "TranslateMessage absent");
        }
    }

    private Method resolveNewTabSource() {
        try {
            Class<?> type = Reflect.cls(loader, TAB_CREATOR_UTIL);
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || !"launchNtp".equals(method.getName())
                        || method.getReturnType() != void.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 3 || p[2] != int.class) continue;
                if (!p[0].getName().endsWith(".TabCreator")) continue;
                method.setAccessible(true);
                return method;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Resolve a stable semantic method only when its signature bucket is unambiguous. */
    private Method method(String owner, String name, int parameterCount) {
        if (owner == null || name == null) return null;
        try {
            Class<?> type = Reflect.cls(loader, owner);
            Method found = null;
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || Modifier.isAbstract(method.getModifiers())
                        || method.getParameterCount() != parameterCount) continue;
                if (found != null) return null;
                method.setAccessible(true);
                found = method;
            }
            if (found != null) return found;

            for (Method method : type.getMethods()) {
                if (!name.equals(method.getName()) || Modifier.isAbstract(method.getModifiers())
                        || method.getParameterCount() != parameterCount) continue;
                if (found != null) return null;
                method.setAccessible(true);
                found = method;
            }
            return found;
        } catch (Throwable ignored) {}
        return null;
    }

    private void putType(BrowserCapabilities.Builder out, BrowserCapabilities.Key key,
                         boolean available, String detail) {
        if (available) out.available(key, sourceForStable(), confidenceForStable(), detail);
        else out.unavailable(key, detail + " absent");
    }

    private BrowserCapabilities.Source sourceForStable() {
        return profile.isVerifiedExact()
                ? BrowserCapabilities.Source.VERIFIED_EXACT
                : BrowserCapabilities.Source.STABLE_API;
    }

    private int confidenceForStable() { return profile.isVerifiedExact() ? 100 : 90; }

    private boolean hasClass(String name) {
        if (name == null || name.isBlank()) return false;
        try { Reflect.cls(loader, name); return true; }
        catch (Throwable ignored) { return false; }
    }

    private boolean hasMethod(String owner, String name) {
        if (owner == null || name == null) return false;
        try {
            Class<?> type = Reflect.cls(loader, owner);
            List<Method> methods = Reflect.named(type, name);
            for (Method method : methods) {
                if (!Modifier.isAbstract(method.getModifiers())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String firstOwnerWithMethod(String method, String... owners) {
        for (String owner : owners) if (hasMethod(owner, method)) return owner;
        return null;
    }

    private boolean hasBooleanStringMethod(String owner) {
        try {
            Class<?> type = Reflect.cls(loader, owner);
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != boolean.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String describe(Method method) {
        return method == null ? "<none>"
                : method.getDeclaringClass().getName() + '#' + method.getName();
    }
}
