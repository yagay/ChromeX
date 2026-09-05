package com.yagay.chromex;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Root installer for the Google-signed Aluminium Chrome + matching Trichrome pair. */
public final class AluminiumInstallerActivity extends Activity {
    static final String CHROME_PACKAGE = "com.android.chrome";
    static final String TRICHROME_PACKAGE = "com.google.android.trichromelibrary";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView help;
    private Button install;
    private Button refresh;
    private volatile AluminiumReleaseCatalog.Release target;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density + .5f);
        box.setPadding(p, p, p, p);
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Aluminium 一键安装");
        title.setTextSize(26f);
        box.addView(title);

        help = new TextView(this);
        help.setText("支持官方 Chromium Desktop Android 扩展插件功能。\n正在读取 ChromeX 仓库稳定通道…");
        help.setTextSize(15f);
        help.setPadding(0, p / 2, 0, p / 2);
        box.addView(help);

        state = new TextView(this);
        state.setTextSize(15f);
        box.addView(state);

        install = new Button(this);
        install.setText("读取版本中…");
        install.setEnabled(false);
        install.setOnClickListener(v -> beginInstall());
        box.addView(install);

        refresh = new Button(this);
        refresh.setText("刷新仓库版本与状态");
        refresh.setOnClickListener(v -> refreshRemoteAndState());
        box.addView(refresh);

        setContentView(scroll);
        refreshRemoteAndState();
    }

    private void refreshRemoteAndState() {
        install.setEnabled(false);
        refresh.setEnabled(false);
        state.setText("读取远程 stable channel…");
        new Thread(() -> {
            try {
                AluminiumReleaseCatalog.Release r = AluminiumReleaseCatalog.fetchStable();
                target = r;
                main.post(() -> {
                    help.setText("支持官方 Chromium Desktop Android 扩展插件功能。\n"
                            + "从 ChromeX GitHub Release 下载 Google 原版 Aluminium Chrome 与匹配 Trichrome。\n"
                            + "安装前校验文件大小、SHA-256、包名、版本号与 Google 证书；只使用 Root PackageManager 的降级能力，"
                            + "不会全局关闭 Android 签名校验。\n\n仓库稳定版：" + r.versionName
                            + " (" + r.versionCode + ")\nBuild：" + r.build + "\nRelease：" + r.tag);
                    install.setText("一键安装 Aluminium " + major(r.versionName));
                    install.setEnabled(true);
                    refresh.setEnabled(true);
                    refreshState(r);
                });
            } catch (Throwable t) {
                target = null;
                main.post(() -> {
                    state.setText("远程版本读取失败：" + safeMessage(t)
                            + "\n请检查网络或 aluminium-assets/channel.json");
                    install.setText("远程版本不可用");
                    install.setEnabled(false);
                    refresh.setEnabled(true);
                });
            }
        }, "ChromeX-AluminiumCatalog").start();
    }

    private void refreshState(AluminiumReleaseCatalog.Release r) {
        String chrome = packageVersion(CHROME_PACKAGE);
        String tri = packageVersion(TRICHROME_PACKAGE);
        state.setText("Root: " + (hasRoot() ? "可用" : "不可用")
                + "\nChrome: " + chrome
                + "\nTrichrome: " + tri
                + "\n仓库目标: " + r.versionName + " · " + r.tag);
    }

    private void beginInstall() {
        AluminiumReleaseCatalog.Release r = target;
        if (r == null) {
            Toast.makeText(this, "请先刷新远程版本", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasRoot()) {
            Toast.makeText(this, "未获得 Root，无法执行降级覆盖安装", Toast.LENGTH_LONG).show();
            return;
        }
        install.setEnabled(false);
        refresh.setEnabled(false);
        state.setText("准备下载 " + r.versionName + "…");
        new Thread(() -> {
            String dirName = "aluminium-" + r.versionCode;
            File dir = new File(getCacheDir(), dirName);
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建缓存目录");
                File chrome = new File(dir, r.chrome.file);
                File tri = new File(dir, r.trichrome.file);

                download(r.releaseBase() + r.trichrome.file, tri, r.trichrome, "下载 Trichrome");
                download(r.releaseBase() + r.chrome.file, chrome, r.chrome, "下载 Chrome");
                verifyArchive(tri, r.trichrome, r);
                verifyArchive(chrome, r.chrome, r);
                verifyInstalledSignatureCompatibility(chrome, CHROME_PACKAGE);
                verifyInstalledSignatureCompatibility(tri, TRICHROME_PACKAGE);

                post("停止 Chrome…");
                root("am force-stop " + CHROME_PACKAGE);
                post("安装 Trichrome " + r.versionName + "…");
                requireSuccess(root("pm install -r -d --user 0 " + shq(tri.getAbsolutePath())), "Trichrome 安装失败");
                post("安装 Chrome Aluminium " + r.versionName + "…");
                requireSuccess(root("pm install -r -d --user 0 " + shq(chrome.getAbsolutePath())), "Chrome 安装失败");

                requireVersion(TRICHROME_PACKAGE, r);
                requireVersion(CHROME_PACKAGE, r);
                post("安装成功：Chrome 与 Trichrome 均为 " + r.versionName);
                main.post(() -> {
                    install.setEnabled(true);
                    refresh.setEnabled(true);
                    refreshState(r);
                    Toast.makeText(this, "Aluminium " + r.versionName + " 安装完成，请重启 Chrome", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                post("失败：" + safeMessage(t));
                main.post(() -> {
                    install.setEnabled(true);
                    refresh.setEnabled(true);
                });
            }
        }, "ChromeX-AluminiumInstaller").start();
    }

    private void download(String url, File out, AluminiumReleaseCatalog.Asset asset, String label) throws Exception {
        if (out.isFile()) {
            if (out.length() == asset.size && asset.sha256.equalsIgnoreCase(sha256(out))) return;
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        post(label + "…");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ChromeX/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException(label + " HTTP " + code);
        long total = c.getContentLengthLong();
        if (total > 0 && total != asset.size) throw new SecurityException(label + " 文件大小与远程清单不一致");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[128 * 1024];
            long done = 0;
            int n;
            long last = 0;
            while ((n = in.read(buf)) >= 0) {
                os.write(buf, 0, n);
                done += n;
                if (done - last >= 8L * 1024 * 1024) {
                    last = done;
                    long pct = asset.size > 0 ? done * 100 / asset.size : -1;
                    post(label + (pct >= 0 ? " " + pct + "%" : " " + done / 1024 / 1024 + "MB"));
                }
            }
        } finally {
            c.disconnect();
        }
        if (out.length() != asset.size) throw new SecurityException(label + " 下载后文件大小不匹配");
    }

    private void verifyArchive(File apk, AluminiumReleaseCatalog.Asset asset,
                               AluminiumReleaseCatalog.Release release) throws Exception {
        post("校验 " + asset.packageName + "…");
        if (apk.length() != asset.size) throw new SecurityException("APK 大小不匹配: " + asset.packageName);
        if (!asset.sha256.equalsIgnoreCase(sha256(apk))) throw new SecurityException("APK SHA-256 不匹配: " + asset.packageName);
        PackageManager pm = getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null) throw new SecurityException("无法解析 APK: " + asset.packageName);
        if (!asset.packageName.equals(pi.packageName)) throw new SecurityException("包名错误: " + pi.packageName);
        if (pi.getLongVersionCode() != release.versionCode || !release.versionName.equals(pi.versionName)) {
            throw new SecurityException("版本错误: " + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        }
        String cert = archiveCertSha256(pi);
        if (!asset.certificateSha256.equalsIgnoreCase(cert)) throw new SecurityException("Google 证书不匹配: " + asset.packageName);
    }

    private void verifyInstalledSignatureCompatibility(File targetFile, String pkg) throws Exception {
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo archive = getPackageManager().getPackageArchiveInfo(targetFile.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null) return;
            String oldCert = archiveCertSha256(installed);
            String newCert = archiveCertSha256(archive);
            if (!oldCert.equalsIgnoreCase(newCert)) {
                throw new SecurityException(pkg + " 当前签名与 Aluminium 目标签名不同，拒绝绕过系统签名校验");
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private String archiveCertSha256(PackageInfo pi) throws Exception {
        if (pi.signingInfo == null) throw new SecurityException("缺少签名信息");
        Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
        if (sigs == null || sigs.length == 0) throw new SecurityException("没有签名证书");
        return hex(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()));
    }

    private void requireVersion(String pkg, AluminiumReleaseCatalog.Release r) throws Exception {
        PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
        if (pi.getLongVersionCode() != r.versionCode || !r.versionName.equals(pi.versionName)) {
            throw new IllegalStateException(pkg + " 安装后版本不匹配: " + pi.versionName);
        }
    }

    private String packageVersion(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "未安装/不可见";
        }
    }

    private boolean hasRoot() {
        try {
            return root("id -u").trim().equals("0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String root(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) >= 0) bos.write(b, 0, n);
        }
        int rc = p.waitFor();
        String out = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        if (rc != 0) throw new IllegalStateException("Root command failed(" + rc + "): " + out.trim());
        return out;
    }

    private static void requireSuccess(String output, String message) {
        String s = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (!s.contains("success")) throw new IllegalStateException(message + ": " + output);
    }

    private static String shq(String v) {
        return "'" + v.replace("'", "'\\''") + "'";
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) md.update(b, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    private static String major(String versionName) {
        int i = versionName.indexOf('.');
        return i > 0 ? versionName.substring(0, i) : versionName;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void post(String text) {
        main.post(() -> state.setText(text));
    }
}
