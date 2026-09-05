# ChromeX Plus — Extension Support

The `plus` branch adds an extension compatibility layer without changing `main`.

## Runtime modes

ChromeX probes the target browser after its Chromium runtime is ready and selects one of three modes:

- **FULL** — the browser already ships Chromium Extension Core and exposes a compatible Android Java bridge. ChromeX resolves the bridge dynamically and can use native extension listing, CRX install/uninstall and extension action execution when those exact compatible methods are present.
- **LITE** — stock Chrome/Chromium has no complete Extension Core. ChromeX provides a deliberately limited compatibility runtime for content-script style extensions.
- **NONE** — required Chromium browser anchors are unavailable, so extension support is not installed.

If Extension Core is detected but the vendor Java bridge does not match a safely callable signature, ChromeX falls back to LITE instead of invoking guessed JNI methods.

## Capability detection

Java anchors include `ChromeTabbedActivity`, `WindowAndroid`, `Profile`, `WebContents` and known Android extension bridge classes such as `ExtensionSystemManager`, `ExtensionInstallerBridge` and `ExtensionActionManagerBridge`.

When an ordinary `libchrome.so` filesystem mapping is available, ChromeX scans it for Extension Core markers using a streaming scanner. The scanner does not copy the entire library into the Java heap. A strong set of Android extension bridge classes can also establish FULL mode when the native library is mapped directly from an APK and cannot safely be scanned as a normal file.

## FULL backend

`NativeExtensionBridgeResolver` is reflection-only and fail-closed. It currently recognizes safe, static variants of these operations:

- list extensions: `getExtensions`, `getAllExtensions`, `getExtensionBeans`
- install CRX: `silentInstallCrx`, `installBackground`
- uninstall: `silentUninstallByID` and compatible variants
- action: `executeAction`, `browserAction`

A method is called only when its Java signature is compatible with the requested operation. Invocation failures are caught and written to ChromeX diagnostics.

This mode is intended for browsers such as Lemur/Kiwi-style Chromium builds that already contain the native extension runtime. ChromeX does not copy vendor native code into another browser.

## LITE backend

LITE is the LSPosed-only ceiling for stock Android Chrome without native Extension Core.

Implemented:

- CRX2, CRX3 and ZIP package intake
- safe archive extraction with path traversal, file-count and size limits
- Manifest V2/V3 `manifest.json` parsing
- `content_scripts.matches`
- `content_scripts.exclude_matches`
- JavaScript content scripts
- CSS content scripts
- injection after page load through Chromium `WebContents.evaluateJavaScript`
- basic `chrome.runtime.id` compatibility value
- basic `chrome.storage.local` compatibility polyfill
- local uninstall backend operation
- Chrome Web Store / Edge Add-ons floating install entry on extension detail pages
- completed `.crx` download routing into the selected backend

The Web Store button obtains the CRX through the store update endpoint and leaves the actual download to the target browser. ChromeX then recognizes the completed CRX and installs it through FULL or LITE mode.

## Deliberate LITE limitations

LITE is not presented as full Chrome Extension API compatibility. It currently does **not** provide Chromium's real:

- extension isolated world
- Manifest V3 extension service worker runtime
- `chrome.webRequest`
- `declarativeNetRequest`
- full `chrome.tabs` / `chrome.scripting` event model
- extension process isolation
- native Extension `EventRouter`
- extension popup/browser-action UI on stock Chrome
- true cross-origin extension storage semantics
- native messaging

Content scripts run in a page-world compatibility environment. The `chrome.storage.local` polyfill is intentionally limited and uses page storage namespaced by the local LITE extension ID.

Those missing features require Chromium Extension Core to be compiled into the browser or a substantially larger reimplementation. LSPosed cannot restore C++ code that was removed at compile time.

## Diagnostics

The existing ChromeX diagnostic export records:

- selected mode and backend
- Java capability hits/misses
- native marker hits/misses
- mapped native library path when available
- resolved FULL bridge methods
- LITE page/runtime hook availability
- extension install/injection failures

This is the primary compatibility mechanism for future Chromium versions: prefer semantic capability detection and guarded fallback instead of hard-coding one Chrome version.

## Safety and maintenance rules

1. `main` remains unchanged; Plus development stays on `plus`.
2. Never invoke a vendor extension JNI bridge by guessed parameter order or guessed native pointer.
3. FULL requires an existing extension runtime; LITE never claims service-worker or full Chrome Web Store compatibility.
4. Unknown builds fall back safely rather than crashing the browser.
5. Native binary probing must remain streaming/read-only.
