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
