package com.yagay.chromex;

/**
 * Semantic anchors verified from the stock Chromium ChromePublic 148.0.7778.288 build.
 *
 * <p>This is deliberately not a version profile. These strings/types come from Chromium feature
 * semantics (trace names, preference keys and stable interface types) and are used by generic
 * resolvers across versions and vendor forks. The class name documents why an anchor exists while
 * feature code remains version-agnostic.</p>
 */
final class Chromium148Reference {
    static final String TAB = "org.chromium.chrome.browser.tab.Tab";
    static final String LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";
    static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    static final String DOWNLOAD_ITEM = "org.chromium.chrome.browser.download.DownloadItem";
    static final String DOWNLOAD_COLLECTION_BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";

    static final String[] HOMEPAGE_SEMANTIC_STRINGS = {
            "Chrome.Homepage.CustomGurl",
            "homepage_custom_uri",
            "Chrome.Homepage.UseNTP",
            "newtabpage_is_homepage"
    };

    static final String[] TAB_CREATOR_SEMANTIC_STRINGS = {
            "ChromeTabCreator.createNewTab",
            "ChromeTabCreator.loadUrlWithSpareTab",
            "ChromeTabCreator.loadUrl"
    };

    private Chromium148Reference() {}
}
