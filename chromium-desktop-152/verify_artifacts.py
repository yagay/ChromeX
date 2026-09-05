#!/usr/bin/env python3
import sys
import zipfile
from pathlib import Path

src = Path(sys.argv[1]).resolve()
out = (src / sys.argv[2]).resolve() if not Path(sys.argv[2]).is_absolute() else Path(sys.argv[2])
artifacts = Path(sys.argv[3]).resolve()

java_markers = [
    b"org/chromium/chrome/browser/ui/extensions/ExtensionActionsBridge",
    b"org/chromium/chrome/browser/ui/extensions/ExtensionActionPopupContents",
    b"org/chromium/chrome/browser/ui/extensions/ExtensionInstallDialogBridge",
    b"org/chromium/chrome/browser/ui/extensions/windowing/ExtensionWindowControllerBridgeImpl",
]

native_markers = [
    b"ExtensionRegistry",
    b"ExtensionFunctionDispatcher",
    b"ToolbarActionsModel",
    b"ExtensionViewHost",
    b"chrome-extension://",
]

bundles = sorted(artifacts.glob("TrichromeChrome*.aab"))
if not bundles:
    # Some Chromium configurations emit the AAB in out/apks but copying may use a variant name.
    bundles = sorted((out / "apks").glob("TrichromeChrome*.aab"))
if not bundles:
    raise SystemExit("No TrichromeChrome AAB found; build did not produce the browser bundle")

bundle = bundles[0]
blob = bytearray()
with zipfile.ZipFile(bundle) as zf:
    dex_names = [n for n in zf.namelist() if n.endswith(".dex")]
    if not dex_names:
        raise SystemExit(f"No DEX files found in {bundle}")
    for name in dex_names:
        blob.extend(zf.read(name))

missing_java = [m.decode() for m in java_markers if m not in blob]
if missing_java:
    raise SystemExit("Desktop Android extension Java bridges missing from built AAB: " + ", ".join(missing_java))
print("Java extension bridges verified in", bundle.name)

native_candidates = [
    out / "lib.unstripped/libmonochrome_64.so",
    out / "lib.unstripped/libmonochrome.so",
]
native = next((p for p in native_candidates if p.is_file()), None)
if native is None:
    raise SystemExit("No unstripped libmonochrome native library found")

# Stream marker scan: do not load a multi-hundred-MiB library into Python memory.
remaining = set(native_markers)
max_len = max(map(len, native_markers))
carry = b""
with native.open("rb") as fh:
    while remaining:
        chunk = fh.read(1024 * 1024)
        if not chunk:
            break
        window = carry + chunk
        for marker in list(remaining):
            if marker in window:
                remaining.remove(marker)
        carry = window[-(max_len - 1):]
if remaining:
    raise SystemExit("Extension Core native markers missing: " + ", ".join(m.decode() for m in remaining))

print("Native Extension Core verified in", native.name)
print("Chromium 152 Desktop Android Extensions artifact validation passed.")
