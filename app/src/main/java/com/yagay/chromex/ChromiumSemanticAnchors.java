package com.yagay.chromex;

/**
 * Cross-version Chromium semantic anchors that survive R8 far more reliably than implementation
 * class names. These values come from stable interface types, TraceEvent names and Chromium
 * preference keys; they are intentionally not tied to any browser package or Chromium version.
 */
final class ChromiumSemanticAnchors {
    static final String TAB = "org.chromium.chrome.browser.tab.Tab";
    static final String LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";
    static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    static final String CONTENT_ID =
            "org.chromium.components.offline_items_collection.ContentId";
    static final String OFFLINE_CONTENT_AGGREGATOR_BRIDGE =
            "org.chromium.components.offline_items_collection.OfflineContentAggregatorBridge";
    static final String DOWNLOAD_ITEM = "org.chromium.chrome.browser.download.DownloadItem";
    static final String DOWNLOAD_COLLECTION_BRIDGE =
            "org.chromium.components.download.DownloadCollectionBridge";

    static final String OFFLINE_RENAME_SEMANTIC =
            "org_chromium_components_offline_1items_1collection_OfflineContentAggregatorBridge_renameItem";

    static final String[] HOMEPAGE_STRINGS = {
            "Chrome.Homepage.CustomGurl",
            "homepage_custom_uri",
            "Chrome.Homepage.UseNTP",
            "newtabpage_is_homepage"
    };

    static final String[] TAB_CREATOR_STRINGS = {
            "ChromeTabCreator.createNewTab",
            "ChromeTabCreator.loadUrlWithSpareTab",
            "ChromeTabCreator.loadUrl"
    };

    private ChromiumSemanticAnchors() {}
}
