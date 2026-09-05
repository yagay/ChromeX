package com.yagay.chromex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Resolves semantic Chromium capabilities without relying on package names or R8 symbols. */
final class ChromiumCapabilityResolver {
    private static final String COMMAND_LINE = "org.chromium.base.CommandLine";
    private static final String PREF_SERVICE = "org.chromium.components.prefs.PrefService";
    private static final String DUPLICATE_BRIDGE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String DOWNLOAD_DIALOG =
            "org.chromium.chrome.browser.download.DownloadDialogBridge";

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final ClassLoader loader;

    ChromiumCapabilityResolver(ChromiumProfile profile, ChromeRuntime runtime, HookSupport hooks) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
        this.loader = runtime.classLoader;
    }

    BrowserCapabilities resolve() {
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
        return capabilities;
    }

    private void resolveCore(BrowserCapabilities.Builder out) {
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
            Method creator = AdaptiveDexResolver.resolveTabCreator(runtime, hooks);
            if (creator != null) {
                out.available(BrowserCapabilities.Key.TAB_CREATOR,
                        BrowserCapabilities.Source.SEMANTIC_DEX, 95,
                        creator.getDeclaringClass().getName() + '#' + creator.getName());
            } else if (hasClass(ChromiumSemanticAnchors.LOAD_URL_PARAMS)
                    && hasClass(ChromiumSemanticAnchors.TAB) && tabModel) {
                out.available(BrowserCapabilities.Key.TAB_CREATOR,
                        BrowserCapabilities.Source.LIVE_RUNTIME, 72,
                        "resolve LoadUrlParams -> Tab creator from live graph");
            } else {
                out.unavailable(BrowserCapabilities.Key.TAB_CREATOR, "creator anchors absent");
            }
        }

        if (profile.isVerifiedExact() && (hasClass(Chrome145.HOMEPAGE_MANAGER)
                || hasClass(profile.is145() ? Chrome145.HOMEPAGE : Chrome152.HOMEPAGE))) {
            out.available(BrowserCapabilities.Key.HOMEPAGE,
                    BrowserCapabilities.Source.VERIFIED_EXACT, 100, profile.label());
        } else {
            Method homepage = AdaptiveDexResolver.resolveHomepageGetter(runtime, hooks);
            if (homepage != null) {
                out.available(BrowserCapabilities.Key.HOMEPAGE,
                        BrowserCapabilities.Source.SEMANTIC_DEX, 95,
                        homepage.getDeclaringClass().getName() + '#' + homepage.getName());
            } else if (hasClass(PREF_SERVICE)) {
                out.available(BrowserCapabilities.Key.HOMEPAGE,
                        BrowserCapabilities.Source.STRUCTURAL, 65,
                        "PrefService fallback available");
            } else {
                out.unavailable(BrowserCapabilities.Key.HOMEPAGE, "homepage binding unresolved");
            }
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

        String completion = firstOwnerWithMethod("onDownloadCompleted",
                Chrome145.DOWNLOAD_CONTROLLER, Chrome145.DOWNLOAD_MANAGER_SERVICE);
        if (completion != null && info) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_COMPLETION,
                    sourceForStable(), confidenceForStable(), completion + "#onDownloadCompleted(*)");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_COMPLETION,
                    "onDownloadCompleted unresolved");
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
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_HISTORY,
                    "standard history callbacks absent; vendor/new backend possible");
        }

        Method offline = DownloadOfflineItemBinding.resolve(loader);
        if (offline != null) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_UI,
                    "createOfflineItem".equals(offline.getName()) ? sourceForStable()
                            : BrowserCapabilities.Source.STRUCTURAL,
                    "createOfflineItem".equals(offline.getName()) ? confidenceForStable() : 92,
                    offline.getDeclaringClass().getName() + '#' + offline.getName()
                            + "(DownloadItem)->OfflineItem");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_OFFLINE_UI,
                    "OfflineItem materializer unresolved");
        }

        if (hasClass(ChromiumSemanticAnchors.OFFLINE_CONTENT_AGGREGATOR_BRIDGE)
                && hasClass("org.chromium.base.Callback") && offline != null) {
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

        if (hasMethod(Chrome145.DOWNLOAD_UTILS, "openDownload")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_OPEN,
                    sourceForStable(), confidenceForStable(), "DownloadUtils#openDownload");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_OPEN, "openDownload absent");
        }

        if (hasMethod(DOWNLOAD_DIALOG, "showDialog")) {
            out.available(BrowserCapabilities.Key.DOWNLOAD_LOCATION_DIALOG,
                    sourceForStable(), confidenceForStable(), DOWNLOAD_DIALOG + "#showDialog");
        } else {
            out.unavailable(BrowserCapabilities.Key.DOWNLOAD_LOCATION_DIALOG,
                    "download location dialog absent");
        }
    }

    private void resolveMisc(BrowserCapabilities.Builder out) {
        if (hasClass(Chrome145.TRANSLATE_MESSAGE)) {
            out.available(BrowserCapabilities.Key.TRANSLATE_MESSAGE,
                    sourceForStable(), confidenceForStable(), Chrome145.TRANSLATE_MESSAGE);
        } else {
            out.unavailable(BrowserCapabilities.Key.TRANSLATE_MESSAGE, "TranslateMessage absent");
        }
    }

    private void putType(BrowserCapabilities.Builder out, BrowserCapabilities.Key key,
                         boolean available, String detail) {
        if (available) {
            out.available(key, sourceForStable(), confidenceForStable(), detail);
        } else {
            out.unavailable(key, detail + " absent");
        }
    }

    private BrowserCapabilities.Source sourceForStable() {
        return profile.isVerifiedExact()
                ? BrowserCapabilities.Source.VERIFIED_EXACT
                : BrowserCapabilities.Source.STABLE_API;
    }

    private int confidenceForStable() {
        return profile.isVerifiedExact() ? 100 : 90;
    }

    private boolean hasClass(String name) {
        if (name == null || name.isBlank()) return false;
        try {
            Reflect.cls(loader, name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
}
