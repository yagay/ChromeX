package com.yagay.chromex;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements ChromeXApp.Listener {
    private LinearLayout content;
    private TextView status;
    private SharedPreferences prefs;

    private static final String[][] ITEMS = {
            {Config.CLEAN_START, "冷启动只保留主页"},
            {Config.NEWTAB_HOME, "新标签页直接打开主页"},
            {Config.CLEAR_CLOSED_TABS, "退出时清理关闭标签记录"},
            {Config.BYPASS_DANGEROUS, "跳过危险文件确认"},
            {Config.BYPASS_INSECURE, "跳过不安全下载确认"},
            {Config.BYPASS_DUPLICATE, "跳过重复下载确认"},
            {Config.BYPASS_POLICY, "跳过下载策略提示"},
            {Config.BYPASS_LOCATION, "跳过保存位置/重命名确认"},
            {Config.BYPASS_OPEN, "跳过打开文件确认"},
            {Config.AUTO_INSTALL_APK, "APK 下载完成后打开安装器"},
            {Config.APK_TOAST, "APK 下载完成改用 Toast"},
            {Config.ALL_DOWNLOAD_TOAST, "所有下载完成改用 Toast"},
            {Config.HIDE_TRANSLATE, "隐藏翻译横幅"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        content.setPadding(p, p, p, p);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ChromeX");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Chrome 自适应兼容 · libxposed Modern API 102\n已安装 Chrome: "
                + installedChromeVersion());
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
            status.setText("LSPosed 服务未连接。仍可导出系统日志，但模块内部诊断数据可能为空。\n");
            addDiagnosticsSection();
            return;
        }
        prefs = Config.fromService(service);
        status.setText("已连接 · API " + service.getApiVersion() + " · 作用域 " + service.getScope() + "\n");
        for (String[] item : ITEMS) addSwitch(item[0], item[1]);
        addDiagnosticsSection();
    }

    private void addSwitch(String key, String label) {
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
    }

    private void addDiagnosticsSection() {
        TextView heading = new TextView(this);
        heading.setText("诊断与 Hook 定位");
        heading.setTextSize(20f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(4));
        content.addView(heading);

        TextView help = new TextView(this);
        help.setText("自动扫描稳定类、方法签名和新版 R8 候选，并记录每个 Hook 是否安装成功、是否实际命中。\n"
                + "最后扫描: " + lastScanText());
        help.setTextSize(14f);
        help.setPadding(0, 0, 0, dp(8));
        content.addView(help);

        Switch diagnostic = new Switch(this);
        diagnostic.setText("诊断模式（推荐保持开启）");
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
            Toast.makeText(this, "正在重启 Chrome 触发新一轮自动定位…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean restarted = DiagnosticExporter.restartChrome(this);
                runOnUiThread(() -> {
                    scan.setEnabled(true);
                    Toast.makeText(this,
                            restarted
                                    ? "Chrome 已重新启动。进入 Chrome 操作一次失效功能后再导出。"
                                    : "未能通过 Root 重启 Chrome，请手动强制结束 Chrome 后重新打开。",
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
                    Toast.makeText(this, result.message,
                            result.success ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
                });
            }, "ChromeX-export").start();
        });
        content.addView(export, buttonParams());

        TextView path = new TextView(this);
        path.setText("导出位置：Download/ChromeX/ChromeX-diagnostic-时间.zip\n"
                + "ZIP 会包含 hook_points、hook_install、hook_hits、模块事件、设备/Chrome 版本、"
                + "过滤后的 logcat 和 LSPosed 日志。Root 可用时收集内容最完整。");
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
        if (prefs == null) return "暂无";
        try {
            long value = prefs.getLong(Diagnostics.KEY_LAST_SCAN, 0L);
            if (value <= 0L) return "暂无；请重启 Chrome";
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
