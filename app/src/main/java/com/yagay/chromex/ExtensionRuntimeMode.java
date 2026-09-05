package com.yagay.chromex;

/** Runtime family selected after probing the target Chromium build. */
public enum ExtensionRuntimeMode {
    /** Google/Chromium official Desktop Android extension implementation. */
    GOOGLE_DESKTOP_FULL,
    /** Third-party Chromium fork exposing its own Android extension bridge (Lemur/Kiwi family). */
    VENDOR_FULL,
    /** Stock mobile Chromium without native Extension Core; ChromeX compatibility runtime. */
    LITE,
    /** Not enough Chromium/extension capability to install an extension backend. */
    NONE;

    public boolean isFull() {
        return this == GOOGLE_DESKTOP_FULL || this == VENDOR_FULL;
    }
}
