# ChromeX Chromium Compatibility Architecture

ChromeX targets Chromium capabilities, not browser brands or R8 symbols.

## Resolution order

Every binding must prefer the most stable evidence available:

1. Stable Chromium API/type
2. Generated semantic JNI
3. Semantic DEX strings and call graph
4. Structural signatures and object-conversion relationships
5. Live runtime object graph
6. Verified exact-build fallback

Package names, vendor-specific short class names, and R8 field names must not be used as primary routing keys.

## Runtime pipeline

`ModuleMain` only bootstraps the real Chromium classloader. `ChromiumCapabilityResolver` produces `BrowserCapabilities`, and `ChromiumFeatureOrchestrator` installs shared feature engines from those capabilities.

Verified Chrome 145/152 metadata remains only as a high-confidence fallback. Unknown Chromium forks use the same engines once their semantic bindings resolve.

## Downloads

The canonical modern Chromium path is:

`Download backend -> OfflineContentProvider -> OfflineContentAggregatorBridge -> OfflineItem -> Download UI`

`DownloadInfoAccessor` reads download metadata by stable accessors first, verified exact fields second, and value/shape analysis last.

`DownloadOfflineItemBinding` resolves the `DownloadItem -> OfflineItem` materializer by signature, so R8-renamed method names do not matter.

### Same-name overwrite

`NativeFirstSameNameOverwriteHooks` is a three-tier engine. The preferred solution prevents Chromium from creating a uniquified filename at all.

#### Tier 1: reservation-source conflict policy

Android Chromium confirms a duplicate and then asks `DownloadPathReservationTracker` for the requested path with the `UNIQUIFY` policy. `DownloadConflictPolicyBinding` virtualizes an overwrite policy without patching native code:

1. Capture the duplicate target while the original file still exists.
2. Snapshot the directory and atomically move the old original to a hidden same-directory transaction backup.
3. Confirm the duplicate through Chromium's normal callback.
4. Chromium runs its own `GetReservedPath(... UNIQUIFY ...)`; because the original path is now free, it can reserve the original filename instead of `name (1).ext`.
5. On successful completion, keep the Chromium-created original-name file and delete the old backup.
6. On confirmation failure, unresolved completion or timeout, restore the old file when the target is still free.
7. On browser-process restart, recover stale transaction backups before starting another overwrite.

This tier depends on semantic duplicate/completion/DownloadInfo capabilities rather than a browser package or Chromium version. The capability report exposes it as `DOWNLOAD_CONFLICT_POLICY`.

#### Tier 2: Chromium source-of-truth rename

If a backend/fork still creates a numbered file, ChromeX captures the DownloadItem/OfflineItem ContentId and calls `OfflineContentAggregatorBridge` RenameItem with the original filename. Chromium then updates its real DownloadItem, OfflineItem and observers.

Generated semantic JNI is preferred. Stock Chromium builds that compress JNI into `J.N` are resolved from the caller's DEX; `DexNativeSelectorResolver` reads the selector constant from that build instead of hard-coding it.

#### Tier 3: filesystem compatibility fallback

If neither reservation-source preservation nor Chromium source rename succeeds, ChromeX uses a narrow transactional same-directory filesystem replacement, then reconciles DownloadInfo/history/media metadata. Browsers that expose the legacy `DownloadManagerService#getAllDownloads` capability receive a backend refresh; browsers without that method are left untouched.

## Homepage and new tabs

Homepage resolution is based on Chromium's semantic data flow (`PrefService` homepage preference -> GURL), not a `HomepageManager` implementation name.

Tab-model discovery uses stable types and structural methods such as `(boolean) -> TabModel`. Tab creation uses the semantic shape `LoadUrlParams ... -> Tab` and live object graphs where necessary.

## Compatibility policy

When a new Chromium-family browser is added:

- First inspect the capability report and diagnostic export.
- Add a new semantic/structural binding only when a capability cannot be resolved.
- Do not add a browser-specific feature implementation when the behavior can be expressed by an existing capability.
- Do not hard-code new R8 short names or JNI selectors unless they are isolated as an exact verified fallback.
- Keep physical-file, history/UI and exact-build workarounds as fallbacks behind the canonical Chromium source path.

## Diagnostics

Important runtime logs include:

- `Chromium capabilities resolved`
- `three-tier same-name overwrite installed`
- `same-name overwrite reservation armed`
- `same-name overwrite preserved original at reservation source`
- `offline rename binding installed`
- `offline rename structural JNI resolved`
- `offline source rename requested`
- `same-name overwrite source normalized`
- `same-name overwrite fallback normalized`
- `download backend refresh requested after normalization`
- `tab creator capability bound`
- `universal cold start settled`

These logs are intended to make a future browser/version diagnosable without first writing browser-specific Hook code.
