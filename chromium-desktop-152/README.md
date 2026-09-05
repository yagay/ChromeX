# Chromium 152 Desktop Android Extensions build

This directory builds Chromium **152.0.7977.75** as an arm64 Desktop Android / Trichrome browser so Chromium's own Desktop Android extension path is compiled instead of transplanting Chrome 145 binaries.

## Why this route

Chromium derives `enable_desktop_android_extensions` from `is_desktop_android`, and derives `enable_extensions_core` from the desktop/mobile extension build flags. The public Android build exposes Trichrome Chrome and Trichrome Library targets. This build therefore uses Chromium 152's own Extension Core, Android toolbar bridge, popup bridge, install dialog, window controller and WebUI implementation.

It intentionally does **not** copy Chrome 145 Java classes, JNI tables, Trichrome libraries or native objects into Chrome 152.

## Outputs

The script builds:

- `//chrome/android:trichrome_chrome_64_bundle`
- `//chrome/android:trichrome_library_64_apk`

Expected collected artifacts include a `TrichromeChrome*.aab`, `TrichromeLibrary*.apk`, build args, commit/tag metadata, and—when emitted by the selected configuration—`libmonochrome_64.so`.

These are Chromium-branded development artifacts (`org.chromium.chrome`), not Google-signed `com.android.chrome` packages. They cannot replace Play-distributed Chrome without using a matching package/signature strategy in your own build.

## Required builder

A full Chromium Android checkout/build is intentionally not run on a normal GitHub-hosted runner. Use Linux x86_64 with a large SSD and substantial RAM. A practical starting point is roughly 200 GB free disk, 32 GB RAM, and 16+ CPU threads; more improves build time.

For GitHub Actions, attach a self-hosted runner with labels:

```text
self-hosted
linux
x64
chromium-builder
```

Then manually run **Chromium 152 Desktop Android Full Build**.

## Local build

```bash
chmod +x chromium-desktop-152/build.sh
CHROMIUM_WORKDIR=/fast-ssd/chromium152 chromium-desktop-152/build.sh
```

Optional environment variables:

```text
CHROMIUM_WORKDIR   checkout/work directory
CHROMIUM_OUT       GN output directory relative to src (default out/ChromeXDesktopAndroid152)
NINJA_JOBS         parallel Ninja jobs
SKIP_BUILD_DEPS=1  skip install-build-deps on an already prepared machine
```

## Safety checks

Before compiling, `verify_gn.py` requires all of these to resolve true:

```text
is_desktop_android
enable_desktop_android_extensions
enable_extensions_core
```

It also checks that the official Chromium source files still contain the Desktop Android extension gates.

After compiling, `verify_artifacts.py` checks the generated browser bundle for official Android extension bridge classes including:

```text
ExtensionActionsBridge
ExtensionActionPopupContents
ExtensionInstallDialogBridge
ExtensionWindowControllerBridgeImpl
```

and checks the unstripped Monochrome native library for representative Extension Core markers such as:

```text
ExtensionRegistry
ExtensionFunctionDispatcher
ToolbarActionsModel
ExtensionViewHost
chrome-extension://
```

A build is considered successful only if these checks pass; merely producing an APK/AAB is not enough.

## Relationship to ChromeX Plus

ChromeX Plus now separates extension runtime families:

```text
GOOGLE_DESKTOP_FULL  Chromium/Google Desktop Android implementation
VENDOR_FULL          Lemur/Kiwi-style vendor bridge
LITE                 compatibility content-script runtime for ordinary mobile Chromium
NONE                 unsupported target
```

A Chromium build produced here should be detected as `GOOGLE_DESKTOP_FULL`, allowing ChromeX to use only the official Desktop Android gate/diagnostics rather than installing its LITE compatibility path.
