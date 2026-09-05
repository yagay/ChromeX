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

/** Root installer for the Google-signed Aluminium Chrome 145 + matching Trichrome pair. */
public final class AluminiumInstallerActivity extends Activity {
    static final String VERSION_NAME = "145.0.7632.218";
    static final long VERSION_CODE = 763221864L;
    static final String CHROME_PACKAGE = "com.android.chrome";
    static final String TRICHROME_PACKAGE = "com.google.android.trichromelibrary";

    // Stored outside the ChromeX APK. Publish these exact asset names in the repository release.
    static final String RELEASE_BASE = "https://github.com/yagay/ChromeX/releases/download/aluminium-145/";
    static final String CHROME_ASSET = "Chrome-Aluminium-145.0.7632.218-arm64.apk";
    static final String TRICHROME_ASSET = "TrichromeLibrary-Aluminium-145.0.7632.218-arm64.apk";

    // APKMirror-published file SHA-256 values for the exact Aluminium OS CL2B.260330.037 pair.
    static final String CHROME_SHA256 = "e98864496f5b56d59253f5da997f8d509a47da0d4ce2a2eb98b16a5538a9d387";
    static final String TRICHROME_SHA256 = "dd620578ab8ae923b5262d5d9803acf821e5a1e9f56ef33d13a17acb91577008";
    static final String CHROME_CERT_SHA256 = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    static final String TRICHROME_CERT_SHA256 = "b6198a8d5689b62b96a0aa3829ce2cc67d59497f78c469f8792b2cd9255490a1";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state;
    private Button install;

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
        title.setText("Aluminium 145 一键安装");
        title.setTextSize(26f);
        box.addView(title);

        TextView help = new TextView(this);
        help.setText("从 ChromeX GitHub Release 下载 Google 原版 Aluminium Chrome 145 与匹配 Trichrome。\n"
                + "安装前校验 SHA-256、包名、版本号与 Google 证书；只使用 Root 的 PackageManager 降级能力，"
                + "不会全局关闭 Android 签名校验。\n\n目标版本：" + VERSION_NAME + " (" + VERSION_CODE + ")");
        help.setTextSize(15f);
        help.setPadding(0, p / 2, 0, p / 2);
        box.addView(help);

        state = new TextView(this);
        state.setTextSize(15f);
        box.addView(state);

        install = new Button(this);
        install.setText("一键安装 Aluminium 145");
        install.setOnClickListener(v -> beginInstall());
        box.addView(install);

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refreshState());
        box.addView(refresh);

        setContentView(scroll);
        refreshState();
    }

    private void refreshState() {
        String chrome = packageVersion(CHROME_PACKAGE);
        String tri = packageVersion(TRICHROME_PACKAGE);
        state.setText("Root: " + (hasRoot() ? "可用" : "不可用")
                + "\nChrome: " + chrome
                + "\nTrichrome: " + tri
                + "\n仓库资产: aluminium-145");
    }

    private void beginInstall() {
        if (!hasRoot()) {
            Toast.makeText(this, "未获得 Root，无法执行降级覆盖安装", Toast.LENGTH_LONG).show();
            return;
        }
        install.setEnabled(false);
        state.setText("准备下载…");
        new Thread(() -> {
            File dir = new File(getCacheDir(), "aluminium145");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建缓存目录");
            File chrome = new File(dir, CHROME_ASSET);
            File tri = new File(dir, TRICHROME_ASSET);
            try {
                download(RELEASE_BASE + TRICHROME_ASSET, tri, "下载 Trichrome");
                download(RELEASE_BASE + CHROME_ASSET, chrome, "下载 Chrome");
                verifyArchive(tri, TRICHROME_PACKAGE, TRICHROME_SHA256, TRICHROME_CERT_SHA256);
                verifyArchive(chrome, CHROME_PACKAGE, CHROME_SHA256, CHROME_CERT_SHA256);
                verifyInstalledSignatureCompatibility(chrome, CHROME_PACKAGE);
                verifyInstalledSignatureCompatibility(tri, TRICHROME_PACKAGE);

                post("停止 Chrome…");
                root("am force-stop " + CHROME_PACKAGE);
                // Trichrome first, then Chrome. -r replaces; -d allows a signed downgrade.
                post("安装 Trichrome 145…");
                requireSuccess(root("pm install -r -d --user 0 " + shq(tri.getAbsolutePath())), "Trichrome 安装失败");
                post("安装 Chrome Aluminium 145…");
                requireSuccess(root("pm install -r -d --user 0 " + shq(chrome.getAbsolutePath())), "Chrome 安装失败");

                requireVersion(TRICHROME_PACKAGE);
                requireVersion(CHROME_PACKAGE);
                post("安装成功：Chrome 与 Trichrome 均为 " + VERSION_NAME);
                main.post(() -> {
                    install.setEnabled(true);
                    refreshState();
                    Toast.makeText(this, "Aluminium 145 安装完成，请重启 Chrome", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                post("失败：" + t.getMessage());
                main.post(() -> install.setEnabled(true));
            } finally {
                // Keep verified APKs in cache for retry; Android may clean cache later.
            }
        }, "ChromeX-AluminiumInstaller").start();
    }

    private void download(String url, File out, String label) throws Exception {
        if (out.isFile()) {
            String expected = out.getName().startsWith("Chrome-") ? CHROME_SHA256 : TRICHROME_SHA256;
            if (expected.equalsIgnoreCase(sha256(out))) return;
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
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[128 * 1024];
            long done = 0;
            long total = c.getContentLengthLong();
            int n;
            long last = 0;
            while ((n = in.read(buf)) >= 0) {
                os.write(buf, 0, n);
                done += n;
                if (done - last >= 8L * 1024 * 1024) {
                    last = done;
                    long pct = total > 0 ? done * 100 / total : -1;
                    post(label + (pct >= 0 ? " " + pct + "%" : " " + done / 1024 / 1024 + "MB"));
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private void verifyArchive(File apk, String expectedPackage, String expectedHash, String expectedCert) throws Exception {
        post("校验 " + expectedPackage + "…");
        if (!expectedHash.equalsIgnoreCase(sha256(apk))) throw new SecurityException("APK SHA-256 不匹配: " + expectedPackage);
        PackageManager pm = getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null) throw new SecurityException("无法解析 APK: " + expectedPackage);
        if (!expectedPackage.equals(pi.packageName)) throw new SecurityException("包名错误: " + pi.packageName);
        if (pi.getLongVersionCode() != VERSION_CODE || !VERSION_NAME.equals(pi.versionName)) {
            throw new SecurityException("版本错误: " + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        }
        String cert = archiveCertSha256(pi);
        if (!expectedCert.equalsIgnoreCase(cert)) throw new SecurityException("Google 证书不匹配: " + expectedPackage);
    }

    private void verifyInstalledSignatureCompatibility(File target, String pkg) throws Exception {
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo archive = getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null) return;
            String oldCert = archiveCertSha256(installed);
            String newCert = archiveCertSha256(archive);
            if (!oldCert.equalsIgnoreCase(newCert)) {
                throw new SecurityException(pkg + " 当前签名与 Aluminium 目标签名不同，拒绝绕过系统签名校验");
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Not installed: PackageManager will perform normal signature checks during install.
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

    private void requireVersion(String pkg) throws Exception {
        PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
        if (pi.getLongVersionCode() != VERSION_CODE || !VERSION_NAME.equals(pi.versionName)) {
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
            String out = root("id -u");
            return out.trim().equals("0");
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
        String out = bos.toString(java.nio.charset.StandardCharsets.UTF_8);
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

    private void post(String text) {
        main.post(() -> state.setText(text));
    }
}
