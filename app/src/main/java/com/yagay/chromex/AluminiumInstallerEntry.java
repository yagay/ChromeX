package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Keeps the Aluminium installer entry visible independently of LSPosed service state. */
final class AluminiumInstallerEntry {
    private static final int ENTRY_TAG = 0x43485258; // CHRX

    private AluminiumInstallerEntry() {}

    static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        View root = activity.findViewById(android.R.id.content);
        LinearLayout content = findLinearLayout(root);
        if (content == null) return;

        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer && ((Integer) tag) == ENTRY_TAG) return;
        }

        LinearLayout section = new LinearLayout(activity);
        section.setTag(ENTRY_TAG);
        section.setOrientation(LinearLayout.VERTICAL);
        int p = dp(activity, 8);
        section.setPadding(0, p, 0, p);

        TextView title = new TextView(activity);
        title.setText("Aluminium 浏览器");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        section.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(activity);
        desc.setText("支持官方 Chromium Desktop Android 扩展插件功能。\n"
                + "从 ChromeX 仓库读取远程 stable 通道，下载并校验匹配的 Chrome + Trichrome，再使用 Root 覆盖/降级安装。以后更新 Release 与 channel.json 即可，无需更新 ChromeX。");
        desc.setTextSize(14f);
        desc.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
        section.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button install = new Button(activity);
        install.setText("一键安装 / 更新 Aluminium");
        install.setOnClickListener(v -> activity.startActivity(
                new Intent(activity, AluminiumInstallerActivity.class)));
        section.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // MainActivity keeps title/description/status as its first three children.
        // Insert after them so the installer is near the top; bind() may remove dynamic
        // children, and ChromeXApp will attach this section again immediately afterwards.
        int index = Math.min(3, content.getChildCount());
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
