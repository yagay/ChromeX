# ChromeX · 原生平板标签栏（实验性）

来源：[Cromite `Add-option-to-force-tablet-UI.patch`](https://github.com/uazo/cromite/blob/master/build/patches/Add-option-to-force-tablet-UI.patch)
（上游补丁声明 GPL-2.0-or-later）。ChromeX 的 Java Hook 为参照补丁行为重写的适配代码，
不包含 Cromite 的 Chromium 原版 XML/资源补丁。

## 集成

- 分支从 `plus` 创建，保留原有模块入口与下载/标签功能。
- `Config.FORCE_TABLET_UI` 默认 **false**。
- ChromeX 设置页「Chromium 原生平板标签栏」单独开关，切换后需彻底重启目标浏览器。
- `ModuleMain` → `ChromiumFeatureOrchestrator` → `CromiteTabletUiHooks`，
  继续使用现有 `HookSupport` 和 `RuntimeDiagnostics`；作用域增加 `org.chromium.chrome`。
- 不改系统密度、不更改设备 `smallestScreenWidthDp`，不注入 SystemUI。
- 若加载器找不到 Chromium 的原生类，则记录警告并跳过；不使用臆测的混淆类名。
- Hook 内容：设备平板判断、屏幕 bucket、标签栏显示阈值、原生 40dp 高度、
  Strip 构造初始化、顶部 2dp 留白、工具栏视图顶部间距、查找栏 fallback。

## 限制及验证

Cromite 的补丁修改 **35 个文件**，包括 `res/values/dimens.xml`,
`res/layout/toolbar_tablet.xml`, `TabStripSceneLayer`,
`AppHeaderUtils` 等。仅 LSPosed API 102 的 Java Hook **无法保证等效**
替换这些 APK 的打包资源或 C++/compositor 行为。

编译成功 **≠** 已显示原生标签栏；必须在安装的 Chromium APK 上测试。
某些官方 Release 的 Java 符号经过混淆或 Chromium 采用 split dex，
导致源码类名在运行时不可见。

### 测试步骤

1. 在 ChromeX 启用实验选项，确认 LSPosed 的作用域包含 `org.chromium.chrome`。
2. 强制停止并重新打开 Chromium；观察是否出现原生横向标签栏、是否遮挡网页。
3. 在 ChromeX 导出诊断报告：查找 `chromex:cromite-tablet:` 和 `tablet-ui:`。
4. 无效/异常时关闭开关，强制停止再启动浏览器恢复。
5. 如果 Java Hook 安装成功但原生 Tab Strip 仍不显示，需要进一步分析**该版本
   已安装 APK** 的 XML 布局、资源维度、Compositor 状态，不应盲目添加 Hook。

目标优先为官方 Chromium (`org.chromium.chrome`)。其他 Chromium 衍生浏览器
虽然也在 ChromeX 可选作用域中，但本功能依赖未混淆的 Chromium 源码类，
并不承诺跨厂商有效。

## Chromium 158 崩溃复现与防护（2026-10-16）

来自用户诊断包：`org.chromium.chrome` 158.0.8110.0、ChromeX 1.1.409。
旧实现仅成功 Hook `DeviceFormFactor#isTablet`，另外 5 个平板检测方法和
`HeightTransitionHandler`、`ToolbarLayout`、`StripLayoutHelperManager`
等核心类均未找到。日志随后出现 `Invalid resource ID 0x00000000`、
`Crashing due to uncaught Java exception`、`FATAL EXCEPTION: main`。
记录未包含最深层 Java 原始异常，故属于强关联原因而非已证明的唯一根因。

修复策略：
- 任何 Java 源码锚点、原生标签栏配套类或已打包手机尺寸资源缺失时，
  完整跳过 Force Tablet UI，**不安装 isTablet() Hook**。
- 严禁仅使用单独的 `isTablet() = true` 作为降级方案。
- 显示明确的 `tablet-ui: INCOMPATIBLE layout` 诊断，而非提示成功。
- Chromium 158 在无额外资源适配的情况下将保持原始手机界面，
  这是刻意的安全回退，并非已完成桌面标签功能。
- 后续若需要在此 APK 上实现平板 UI，需要对照**真实安装 APK**的
  minified DEX、资源表以及 native Compositor，再设计受限 Hook。


## V2 Chromium 158 source-anchored probe

Chromium `158.0.8109.0` (closest available public Git tag) confirms:

- `ChromeTabbedActivity.setupCompositorContent` uses `isTablet()` to choose between
  `LayoutManagerChromePhone` and `LayoutManagerChromeTablet`.
- `TopToolbarCoordinator.maybeInitializeTabStripTransitionCoordinator` exits early
  if `ToolbarLayout.getTabStripHeightFromResource()` returns 0.
- `HeightTransitionHandler` has a 412dp width threshold but still depends
  on an initial nonzero strip height.
- Phone resources define `tab_strip_height=0dp` while sw600dp defines 40dp.
- `StripLayoutHelperManager` and `TabStripTopControlLayer` independently
  consume strip height; adjusting only one creates inconsistent compositor state.

**This commit removes all previous write-side tablet hooks from the active
Chromium 158 pathway.** The switch now launches a bounded background DexKit
scan of four known Chromium 158 TraceEvent literals and records the actual
obfuscated owner/method along with resource values and DeviceFormFactor
method signatures in ChromeX's existing exported diagnostics.

Until the resulting actual APK symbol mapping is validated, automatic activation
is intentionally disabled. Installing a compiling module does NOT imply
that the desktop tab strip is visible.

Source links:
- https://github.com/chromium/chromium/blob/158.0.8109.0/chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java
- https://github.com/chromium/chromium/blob/158.0.8109.0/chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/top/TopToolbarCoordinator.java
- https://github.com/chromium/chromium/blob/158.0.8109.0/chrome/android/java/src/org/chromium/chrome/browser/compositor/overlays/strip/StripLayoutHelperManager.java

## 2026-10-16 23:00 diagnostic: Chromium 158 safe isolation

The user uploaded `ChromeX-diagnostic-20261016-230009.zip` with ChromeX
1.1.413 (`d4a2cad8b473`), Chromium 158.0.8110.0 and the tablet scan enabled.

The **read-only probe completed all four anchors**:

- `createLayoutManagerForTablet` => `ChromeTabbedActivity#i3()` => `da8`
- `createLayoutManagerForPhone` => `ChromeTabbedActivity#h3()` => `ba8`
- `ToolbarControlContainer.initWithToolbar` => `ToolbarControlContainer#q(int,int)`
- `setupCompositorContent` => `ChromeTabbedActivity#T3()`

The report's filtered logcat begins at 22:59:53, after the last ChromeX
module event at 22:59:51. The actual crash stack is **not present**.
The probe made **no Hook installations** in the tablet subsystem,
but the existing tabs, homepage, downloads and extension hooks were still
installed and `chromex:universal:tabs:onStart` hit once.

**Isolation change:** Stock `org.chromium.chrome` major 158 now defaults to
safe mode, which skips the entire regular feature orchestrator,
extension probing and deep scanning before they install hooks.
If the tablet source scan is enabled, it runs on its own, with only the
existing ChromeX bootstrap observer. The old tablet write Hooks remain
disabled. This permits testing whether unrelated ChromeX hooks caused
the crash, but does NOT prove the failure's root cause or activate tabs.

A user may explicitly turn safe mode off, but leaving the tablet scan
enabled always enforces isolation. No other browser package is affected.
Disable/enable settings and force-stop Chromium to restart the process
rather than relying on hot reload.

## 23:07 diagnostic and V3 observer

The 23:07 ZIP confirms safe mode=true, scan=true and **4/4 DEX anchors matched**.
The existing source scan is **read-only**, so no horizontal tabs are expected yet.
Packaged resource names are stripped; `Resources.getIdentifier(...)` returned 0
for layout names and dimensions, which does not establish missing resource IDs.

V3 augments the 158-specific read-only probe with synchronous LSPosed
observers for the four **actual** 158.0.8110.0 methods discovered in the ZIP:
`ChromeTabbedActivity.i3()` (tablet manager),
`ChromeTabbedActivity.h3()` (phone manager),
`ChromeTabbedActivity.T3()` (compositor startup), and
`ToolbarControlContainer.q(int,int)` (toolbar inflation).

The observers run the original method with `chain.proceed()`, without
altering arguments, results, resources, layout, or DeviceFormFactor.
They log which native manager was actually constructed and the integer layout/
height resource IDs as passed by Chromium. This yields the exact values needed
to reason about safe tablet initialization instead of guessing numeric R IDs.
Existing Chromium 158 safe isolation remains enabled.

**No native tablet mode is enabled yet:** it cannot safely follow from a
source-method mapping alone; the layout/resource/Compositor prerequisites
must be identified and corrected together.
