package com.yagay.chromex;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AluminiumReleaseCatalog {
    static final String CHANNEL_URL = "https://raw.githubusercontent.com/yagay/ChromeX/main/aluminium-assets/channel.json";

    static final class Asset {
        final String file;
        final String packageName;
        final long size;
        final String sha256;
        final String certificateSha256;

        Asset(String file, String packageName, long size, String sha256, String certificateSha256) {
            this.file = file;
            this.packageName = packageName;
            this.size = size;
            this.sha256 = sha256;
            this.certificateSha256 = certificateSha256;
        }
    }

    static final class Release {
        final String versionName;
        final long versionCode;
        final String tag;
        final String build;
        final Asset chrome;
        final Asset trichrome;

        Release(String versionName, long versionCode, String tag, String build, Asset chrome, Asset trichrome) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.tag = tag;
            this.build = build;
            this.chrome = chrome;
            this.trichrome = trichrome;
        }

        String releaseBase() {
            return "https://github.com/yagay/ChromeX/releases/download/" + tag + "/";
        }
    }

    static Release fetchStable() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(CHANNEL_URL + "?t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "ChromeX/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("channel.json HTTP " + code);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (root.optInt("schema", 0) != 1) throw new IllegalStateException("不支持的 channel schema");
            return parseRelease(root.getJSONObject("stable"));
        } finally {
            c.disconnect();
        }
    }

    private static Release parseRelease(JSONObject o) throws Exception {
        String versionName = required(o, "versionName");
        long versionCode = o.getLong("versionCode");
        String tag = required(o, "tag");
        String build = o.optString("build", "");
        Asset chrome = parseAsset(o.getJSONObject("chrome"));
        Asset tri = parseAsset(o.getJSONObject("trichrome"));
        if (!"com.android.chrome".equals(chrome.packageName)) throw new SecurityException("远程清单 Chrome 包名无效");
        if (!"com.google.android.trichromelibrary".equals(tri.packageName)) throw new SecurityException("远程清单 Trichrome 包名无效");
        return new Release(versionName, versionCode, tag, build, chrome, tri);
    }

    private static Asset parseAsset(JSONObject o) throws Exception {
        String file = required(o, "file");
        String pkg = required(o, "package");
        long size = o.getLong("size");
        String sha = required(o, "sha256").toLowerCase();
        String cert = required(o, "certificateSha256").toLowerCase();
        if (!file.endsWith(".apk") || size <= 0 || sha.length() != 64 || cert.length() != 64) {
            throw new SecurityException("远程资产清单格式无效");
        }
        return new Asset(file, pkg, size, sha, cert);
    }

    private static String required(JSONObject o, String key) throws Exception {
        String v = o.getString(key).trim();
        if (v.isEmpty()) throw new IllegalStateException("远程清单缺少 " + key);
        return v;
    }

    private AluminiumReleaseCatalog() {}
}
