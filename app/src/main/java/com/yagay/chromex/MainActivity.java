package com.yagay.chromex;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements ChromeXApp.Listener {
    private LinearLayout content;
    private TextView status;
    private SharedPreferences prefs;
    private int basePadding;

    private static final String[][] ITEMS = {
            {Config.CLEAN_START, "冷启动只保留主页"},
            {Config.NEWTAB_HOME, "新标签页直接打开主页"},
            {Config.CLEAR_CLOSED_TABS, "退出时清理关闭标签记录"},
            {Config.BYPASS_DANGEROUS, "跳过危险文件确认"},
            {Config.BYPASS_INSECURE, "跳过不安全下载确认"},
            {Config.BYPASS_DUPLICATE, "跳过重复下载确认"},
            {Config.OVERWRITE_DUPLICATE, "同名文件直接覆盖旧文件（优先于重复下载确认）"},
            {Config.BYPASS_POLICY, "跳过下载策略提示"},
            {Config.BYPASS_LOCATION, "跳过保存位置/重命名确认"},
            {Config.BYPASS_OPEN, "跳过打开文件确认"},
            {Config.APK_TOAST, "APK 下载完成改用 Toast"},
            {Config.ALL_DOWNLOAD_TOAST, "所有下载完成改用 Toast"},
            {Config.HIDE_TRANSLATE, "隐藏翻译横幅"}
    };

    private static final String[][] AUTO_OPEN_ITEMS = {
            {Config.AUTO_OPEN_APK, "APK（.apk，系统安装器）"},
            {Config.AUTO_OPEN_APP_BUNDLE, "应用安装包（.apks / .apkm / .xapk）"},
            {Config.AUTO_OPEN_PDF, "PDF（.pdf）"},
            {Config.AUTO_OPEN_ARCHIVE, "压缩包（ZIP / RAR / 7Z / TAR / GZ 等）"},
            {Config.AUTO_OPEN_DOCUMENT, "文档（DOC / DOCX / ODT / RTF）"},
            {Config.AUTO_OPEN_SPREADSHEET, "表格（XLS / XLSX / ODS / CSV）"},
            {Config.AUTO_OPEN_PRESENTATION, "演示文稿（PPT / PPTX / ODP）"},
            {Config.AUTO_OPEN_TEXT, "文本（TXT / MD / JSON / XML / YAML / LOG 等）"},
            {Config.AUTO_OPEN_IMAGE, "图片（JPG / PNG / WEBP / HEIC / AVIF 等）"},
            {Config.AUTO_OPEN_VIDEO, "视频（MP4 / MKV / WEBM / MOV 等）"},
            {Config.AUTO_OPEN_AUDIO, "音频（MP3 / M4A / FLAC / WAV / OPUS 等）"},
            {Config.AUTO_OPEN_EBOOK, "电子书（EPUB / MOBI / AZW3 / FB2）"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        basePadding = dp(20);
        content.setPadding(basePadding, basePadding, basePadding, basePadding);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // targetSdk 37 uses edge-to-edge behavior. Apply real system-bar insets instead of relying
        // on a fixed top/bottom padding, which avoids overlap on Android 16/17 and OEM variants.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            content.setPadding(basePadding + bars.left, basePadding + bars.top,
                    basePadding + bars.right, basePadding + bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("ChromeX");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Chrome 自适应兼容 · Split-ready · DexKit resolver · libxposed API 102\n"
                + "ChromeX: " + BuildConfig.VERSION_NAME
                + " · run " + BuildConfig.BUILD_RUN
                + " · " + BuildConfig.BUILD_SHA + "\n"
                + "已安装 Chrome: " + installedChromeVersion());
        desc.setTextSize(14f);
        desc.setPadding(0, dp(4), 0, dp(12));
        content.addView(desc);

        status = new TextView(this);
        content.addView(status);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChromeXApp.addListener(this, true);
    }

    @Override
    protected void onStop() {
        ChromeXApp.removeListener(this);
        super.onStop();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        runOnUiThread(() -> bind(service));
    }

    private void bind(XposedService service) {
        while (content.getChildCount() > 3) content.removeViewAt(3);
        if (service == null) {
            prefs = null;
            status.setText("LSPosed 服务未连接。仍可导出本地诊断数据，但模块设置不可修改。\n");
            addDiagnosticsSection();
            return;
        }
        prefs = Config.fromService(service);
        status.setText("已连接 · API " + service.getApiVersion()
                + " · 作用域 " + service.getScope() + "\n");
        for (String[] item : ITEMS) addSwitch(item[0], item[1]);
        addAutoOpenSection();
        addDiagnosticsSection();
    }

    private Switch addSwitch(String key, String label) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(16f);
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setChecked(Config.get(prefs, key));
        sw.setOnCheckedChangeListener((button, checked) -> {
            SharedPreferences.Editor editor = prefs == null ? null : prefs.edit();
            if (editor != null) editor.putBoolean(key, checked).apply();
        });
        content.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    private void addAutoOpenSection() {
        TextView heading = new TextView(this);
        heading.setText("下载完成后自动打开");
        heading.setTextSize(20f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(4));
        content.addView(heading);

        TextView help = new TextView(this);
        help.setText("可多选。仅勾选的文件类型会在下载真正完成后自动打开；"
                + "使用 MIME + 扩展名双重识别。没有对应应用时只提示，不会导致 Chrome 崩溃。\n"
                + "APK 继续交给系统安装器；APKS/APKM/XAPK 会交给支持该格式的应用。");
        help.setTextSize(14f);
        help.setPadding(0, 0, 0, dp(6));
        content.addView(help);

        List<Switch> switches = new ArrayList<>();
        for (String[] item : AUTO_OPEN_ITEMS) switches.add(addSwitch(item[0], item[1]));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, dp(4));

        Button selectAll = new Button(this);
        selectAll.setText("全选");
        selectAll.setOnClickListener(v -> setAllAutoOpenTypes(true, switches));
        actions.addView(selectAll, weightedButtonParams());

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> setAllAutoOpenTypes(false, switches));
        actions.addView(clear, weightedButtonParams());

        content.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void setAllAutoOpenTypes(boolean enabled, List<Switch> switches) {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : Config.AUTO_OPEN_KEYS) editor.putBoolean(key, enabled);
        editor.apply();
        for (Switch sw : switches) sw.setChecked(enabled);
        Toast.makeText(this, enabled ? "已全选自动打开类型" : "已清空自动打开类型",
                Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginEnd(dp(4));
        return p;
    }

    private void addDiagnosticsSection() {
        TextView heading = new TextView(this);
        heading.setText("诊断与 Hook 定位");
        heading.setTextSize(20f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(4));
        content.addView(heading);

        TextView help = new TextView(this);
        help.setText("轻量诊断（Hook 安装、命中、错误、版本）始终保留；未知新版优先使用稳定 API 与 DexKit 结构解析。\n"
                + "深度诊断仅用于重新定位 Hook 点，扫描完成后会自动关闭；Resolver 缓存会跨重扫保留。\n"
                + "最后深度扫描: " + lastScanText());
        help.setTextSize(14f);
        help.setPadding(0, 0, 0, dp(8));
        content.addView(help);

        TextView root = new TextView(this);
        root.setText("Root：检测中…");
        root.setTextSize(13f);
        root.setPadding(0, 0, 0, dp(8));
        content.addView(root);
        new Thread(() -> {
            String value = DiagnosticExporter.rootStatus();
            runOnUiThread(() -> root.setText(value));
        }, "ChromeX-root-probe").start();

        Switch diagnostic = new Switch(this);
        diagnostic.setText("深度诊断模式（一次扫描后自动关闭）");
        diagnostic.setTextSize(16f);
        diagnostic.setChecked(Config.get(prefs, Config.DIAGNOSTIC_MODE));
        diagnostic.setPadding(0, dp(6), 0, dp(6));
        diagnostic.setOnCheckedChangeListener((button, checked) -> {
            if (prefs != null) prefs.edit().putBoolean(Config.DIAGNOSTIC_MODE, checked).apply();
        });
        content.addView(diagnostic, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button scan = new Button(this);
        scan.setText("重新定位 Hook 点");
        scan.setOnClickListener(v -> {
            if (prefs != null) prefs.edit().putBoolean(Config.DIAGNOSTIC_MODE, true).apply();
            scan.setEnabled(false);
            Toast.makeText(this, "正在清理旧诊断并重新启动 Chrome…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                DiagnosticProvider.clearStore(this);
                boolean restarted = DiagnosticExporter.restartChrome(this);
                runOnUiThread(() -> {
                    scan.setEnabled(true);
                    Toast.makeText(this,
                            restarted
                                    ? "Chrome 已重新启动。请触发失效功能；深度扫描完成后会自动关闭。"
                                    : "ChromeX 没有可用 Root。旧诊断已清理，请手动强制结束 Chrome 后重新打开。",
                            Toast.LENGTH_LONG).show();
                });
            }, "ChromeX-rescan").start();
        });
        content.addView(scan, buttonParams());

        Button export = new Button(this);
        export.setText("一键导出诊断包");
        export.setOnClickListener(v -> {
            export.setEnabled(false);
            Toast.makeText(this, "正在收集 ChromeX / Chrome / LSPosed 相关日志…",
                    Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                DiagnosticExporter.Result result = DiagnosticExporter.export(this, prefs);
                runOnUiThread(() -> {
                    export.setEnabled(true);
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                });
            }, "ChromeX-export").start();
        });
        content.addView(export, buttonParams());

        TextView path = new TextView(this);
        path.setText("导出位置：Download/ChromeX/ChromeX-diagnostic-时间.zip\n"
                + "诊断包包含 split-ready、resolver、Hook 安装/命中、模块事件、设备/Chrome 版本、"
                + "Root 状态、过滤后的 logcat 和 LSPosed 日志。\n"
                + "Resolver 缓存按 Chrome build 自动失效；Chrome 升级后无需手动清缓存。");
        path.setTextSize(13f);
        path.setPadding(0, dp(8), 0, dp(16));
        content.addView(path);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        return p;
    }

    private String lastScanText() {
        try {
            SharedPreferences diagnosticPrefs = DiagnosticProvider.store(this);
            long value = diagnosticPrefs.getLong(Diagnostics.KEY_LAST_SCAN, 0L);
            if (value <= 0L) return "暂无";
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(value));
        } catch (Throwable ignored) {
            return "读取失败";
        }
    }

    private String installedChromeVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(Chrome145.PACKAGE, 0);
            return info.versionName == null ? "未知" : info.versionName;
        } catch (Throwable ignored) {
            return "未安装/不可读取";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
