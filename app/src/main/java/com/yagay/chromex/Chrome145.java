package com.yagay.chromex;

/**
 * Chrome 145.0.7632.218 compatibility profile.
 * All version-sensitive symbols live here so a future Chrome update does not contaminate feature code.
 */
public final class Chrome145 {
    public static final String PACKAGE = "com.android.chrome";
    public static final String ACTIVITY = "org.chromium.chrome.browser.ChromeTabbedActivity";
    public static final String TAB_MODEL = "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";
    public static final String GURL = "org.chromium.url.GURL";
    public static final String PROFILE_MANAGER = "org.chromium.chrome.browser.profiles.ProfileManager";
    public static final String PROFILE = "org.chromium.chrome.browser.profiles.Profile";
    public static final String WINDOW = "org.chromium.ui.base.WindowAndroid";
    public static final String WEB_CONTENTS = "org.chromium.content_public.browser.WebContents";
    public static final String PROPERTY_MODEL = "org.chromium.ui.modelutil.PropertyModel";
    public static final String OFFLINE_ITEM = "org.chromium.components.offline_items_collection.OfflineItem";
    public static final String OFFLINE_VISUALS = "org.chromium.components.offline_items_collection.OfflineItemVisuals";
    public static final String DOWNLOAD_INFO = "org.chromium.chrome.browser.download.DownloadInfo";
    public static final String DOWNLOAD_CONTROLLER = "org.chromium.chrome.browser.download.DownloadController";
    public static final String DOWNLOAD_UTILS = "org.chromium.chrome.browser.download.DownloadUtils";
    public static final String TRANSLATE_MESSAGE = "org.chromium.components.translate.TranslateMessage";
    public static final String NATIVE = "J.N";

    // R8 names verified against Chrome 145.0.7632.218.
    public static final String COMMAND_FLAGS = "oo4";
    public static final String SELECTOR = "tuo";
    public static final String HOMEPAGE = "jza";
    public static final String CLOSE_ALL_RUNNABLE = "id4";
    public static final String TAB_CREATOR = "l04";
    public static final String DOWNLOAD_EVENT_RUNNABLE = "c9o";
    public static final String OFFLINE_COMPLETE = "zkg";
    public static final String OPEN_DOWNLOAD_REQUEST = "qe7";
    public static final String DOWNLOAD_MESSAGE = "je7";
    public static final String MESSAGE_DISPATCHER = "nze";

    public static final String NTP = "chrome-native://newtab/";

    private Chrome145() {}
}
