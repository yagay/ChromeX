package com.yagay.chromex;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

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
            status.setText("LSPosed 服务未连接。请确认模块已启用。\n");
            return;
        }
        prefs = Config.fromService(service);
        status.setText("已连接 · API " + service.getApiVersion() + " · 作用域 " + service.getScope() + "\n");
        for (String[] item : ITEMS) addSwitch(item[0], item[1]);
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
