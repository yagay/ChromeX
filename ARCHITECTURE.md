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

`NativeFirstSameNameOverwriteHooks` treats Chromium's own download record as the source of truth.

Preferred path:

1. Capture the duplicate-download target.
2. Capture the DownloadItem/OfflineItem ContentId.
3. Temporarily move the previous original file aside.
4. Call `OfflineContentAggregatorBridge` RenameItem with the original filename.
5. Let Chromium update its real DownloadItem, OfflineItem and observers.
6. Verify the target, remove the backup, update the normalization registry and media index.

If source rename cannot be resolved or fails, ChromeX rolls back safely and uses the transactional same-directory filesystem fallback.

Generated semantic JNI is preferred. Stock Chromium builds that compress JNI into `J.N` are resolved from the caller's DEX; `DexNativeSelectorResolver` reads the selector constant from that build instead of hard-coding it.

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
- `offline rename binding installed`
- `offline rename structural JNI resolved`
- `offline source rename requested`
- `same-name overwrite source normalized`
- `same-name overwrite fallback normalized`
- `tab creator capability bound`
- `universal cold start settled`

These logs are intended to make a future browser/version diagnosable without first writing browser-specific Hook code.
