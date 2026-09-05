package com.yagay.chromex;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import io.github.libxposed.service.XposedService;

/** UI for choosing whether overwrite mode keeps Chromium's native duplicate confirmation. */
final class OverwriteConfirmationEntry {
    private static final int ENTRY_TAG = 0x4f565243; // OVRC

    private OverwriteConfirmationEntry() {}

    static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        View root = activity.findViewById(android.R.id.content);
        LinearLayout content = findLinearLayout(root);
        if (content == null) return;

        LinearLayout existing = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer && ((Integer) tag) == ENTRY_TAG && child instanceof LinearLayout) {
                existing = (LinearLayout) child;
                break;
            }
        }
        if (existing != null) content.removeView(existing);

        XposedService service = ChromeXApp.getService();
        SharedPreferences prefs = null;
        try { if (service != null) prefs = Config.fromService(service); }
        catch (Throwable ignored) {}
        final SharedPreferences remotePrefs = prefs;

        LinearLayout section = new LinearLayout(activity);
        section.setTag(ENTRY_TAG);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(activity, 8), 0, dp(activity, 8));

        TextView title = new TextView(activity);
        title.setText("同名覆盖选项");
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        section.addView(title);

        Switch confirm = new Switch(activity);
        confirm.setText("同名覆盖时保留重复下载确认");
        confirm.setTextSize(15f);
        confirm.setChecked(Config.stored(remotePrefs, Config.OVERWRITE_CONFIRM_DUPLICATE));
        confirm.setEnabled(remotePrefs != null);
        confirm.setOnCheckedChangeListener((button, checked) -> {
            if (remotePrefs == null) return;
            remotePrefs.edit().putBoolean(Config.OVERWRITE_CONFIRM_DUPLICATE, checked).apply();
        });
        section.addView(confirm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(activity);
        help.setText("开启：重复下载时显示浏览器原生确认框，确认后仍覆盖原文件且不保留 (1)/(2)。\n"
                + "关闭：不显示重复下载确认，直接覆盖原文件。只有启用“同名文件直接覆盖”时此选项才生效。"
                + (remotePrefs == null ? "\nLSPosed 服务未连接，暂不可修改。" : ""));
        help.setTextSize(13f);
        section.addView(help);

        // Keep it near the top. MainActivity may rebuild its dynamic children; ChromeXApp reattaches.
        int index = Math.min(4, content.getChildCount());
        content.addView(section, index, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout findLinearLayout(View view) {
        if (view instanceof LinearLayout) return (LinearLayout) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            LinearLayout found = findLinearLayout(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
