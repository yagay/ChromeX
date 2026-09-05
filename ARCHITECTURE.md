# ChromeX Chromium Compatibility Architecture

ChromeX targets Chromium decisions and data sources, not browser brands or R8 symbols.

## Resolution order

Every binding must prefer the most stable evidence available:

1. Stable Chromium API/type and source decision
2. Generated semantic JNI
3. Semantic DEX strings and call graph
4. Structural signatures and object-conversion relationships
5. Live runtime object graph
6. Verified exact-build fallback

Package names, vendor-specific short class names, and R8 field names must not be primary routing keys.

## Runtime pipeline

`ModuleMain` only bootstraps the real Chromium classloader. `ChromiumCapabilityResolver` performs one resolution pass and returns `ResolvedBindings`. `BrowserCapabilities` is the human-readable capability report generated from the same pass. `ChromiumFeatureOrchestrator` then installs shared feature engines using those bindings.

Expensive semantic/Dex resolution must not be repeated independently by feature classes. Verified Chrome 145/152 metadata remains only as a last high-confidence fallback.

## Source-first rule

For every feature, prefer the point where Chromium makes the decision or owns the authoritative state. UI/result rewriting is a fallback.

Examples:

- New tab: `TabCreatorUtil.launchNtp` source decision -> creator argument rewrite fallback.
- Download completion: `OfflineContentAggregatorBridge.onItemUpdated` state transition -> legacy `onDownloadCompleted` fallback.
- Download location: `DownloadDialogBridge.getPromptForDownloadAndroid` -> dialog callback fallback.
- Cold start: `TabModelSelectorBase.markTabStateInitialized` -> lifecycle timing fallback.
- Recently closed: `RecentlyClosedBridge.clearRecentlyClosedEntries` -> verified exact JNI fallback.
- Engine version: `VersionInfo.getProductVersion` -> semantic DEX -> version-literal scan -> app version fallback.

## Downloads

Canonical modern Chromium path:

`Download backend -> OfflineContentProvider -> OfflineContentAggregatorBridge -> OfflineItem -> Download UI`

`DownloadInfoAccessor` and `OfflineItemAccessor` read metadata by stable accessors/fields first and value/shape analysis when R8 renames implementation details.

`OfflineContentLifecycleBinding` snapshots OfflineItem integer state and learns an obfuscated state field from a real transition into `OfflineItemState.COMPLETE`. Ambiguous transitions are ignored, allowing the legacy completion callback to remain the safe fallback.

Auto-open prefers Chromium's `OfflineContentProvider.openItem(ContentId)` for normal documents/media. APK installation keeps the URI/FileProvider path because Android package installation has additional permission and content-URI requirements.

### Same-name overwrite

`NativeFirstSameNameOverwriteHooks` is a three-tier engine.

#### Tier 1: reservation-source conflict policy

1. Capture the duplicate target while the original still exists.
2. Atomically move the old original to a hidden same-directory transaction backup.
3. Confirm the duplicate through Chromium's normal callback.
4. Chromium performs its normal `GetReservedPath(... UNIQUIFY ...)`; because the requested path is free, it can reserve the original name instead of `name (1).ext`.
5. Successful completion deletes the old backup.
6. Failure/timeout restores it when safe.
7. Browser restart recovers stale transaction backups.

This is exposed as `DOWNLOAD_CONFLICT_POLICY`.

#### Tier 2: Chromium source-of-truth rename

If a backend still creates a numbered file, ChromeX captures the `ContentId` and calls `OfflineContentAggregatorBridge.renameItem` with the original name. Generated semantic JNI is preferred. Stock Chromium compressed `J.N` selectors are read from the target build's own DEX by `DexNativeSelectorResolver`, never copied from another version.

#### Tier 3: filesystem compatibility fallback

If source reservation and source rename fail, ChromeX uses the narrow transactional same-directory replacement and then reconciles DownloadInfo/history/media metadata. `DownloadManagerService#getAllDownloads` is invoked only when that capability actually exists.

## Homepage and new tabs

Homepage discovery follows Chromium semantic data flow (`PrefService` homepage preference -> GURL). New-tab redirection first intercepts the common `TabCreatorUtil.launchNtp` decision before Chromium materializes the NTP URL. Older/forked builds fall back to `TabModel` and structural `LoadUrlParams -> Tab` creator rewriting.

## Startup and tab history

`no-restore-state` remains the earliest supported restore suppression when available. Otherwise ChromeX prefers Chromium's real tab-state-ready event and only uses delayed lifecycle rounds as a compatibility fallback.

Tab closure uses `TabClosureParams.allowUndo(false)` and `saveToTabRestoreService(false)` whenever possible. If restore history still needs clearing, `RecentlyClosedBridge.clearRecentlyClosedEntries()` is preferred because it reaches Chromium's real TabRestoreService. Chrome 145/152 exact cleaners remain last-resort fallbacks only.

## Download dialogs

Dangerous, insecure, policy and open confirmations continue through Chromium's own accepted/onConfirmed callbacks; ChromeX does not falsify security state fields.

Download-location suppression is different: Chromium already owns a prompt preference source. ChromeX returns `DownloadPromptStatus.DONT_SHOW` from the source getter while the option is enabled. The existing `showDialog` callback interception remains only for builds that do not expose the source getter.

## Compatibility policy

When a new Chromium-family browser is added:

- Inspect the capability report and diagnostic export first.
- Add a semantic/structural binding only when a capability cannot be resolved.
- Do not create browser-specific feature implementations when an existing capability expresses the behavior.
- Do not hard-code R8 short names or JNI selectors except inside isolated verified fallbacks.
- Preserve known working fallbacks when adding a new source-first path.
- A source binding must fail closed: ambiguous resolution means skip it and use the next fallback.

## Diagnostics

Important runtime logs include:

- `Chromium capabilities resolved`
- `Chromium engine version bound from VersionInfo`
- `new-tab redirected at Chromium source`
- `tab restore-ready source bound`
- `cold-start cleanup triggered by tab-state-ready`
- `recently-closed source cleared through RecentlyClosedBridge`
- `OfflineContent lifecycle source bound`
- `OfflineItem state field learned structurally`
- `download completion through universal pipeline ... source=OfflineContentAggregator`
- `download location source policy bound`
- `three-tier same-name overwrite installed`
- `same-name overwrite reservation armed`
- `same-name overwrite preserved original at reservation source`
- `offline source rename requested`
- `same-name overwrite fallback normalized`

These logs are designed to diagnose a new browser/version before any browser-specific Hook is written.
