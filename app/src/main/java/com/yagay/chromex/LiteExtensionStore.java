package com.yagay.chromex;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class LiteExtensionStore {
    private static final String DIR = "chromex_extensions_lite";
    private static final int MAX_FILES = 2048;
    private static final long MAX_TOTAL = 64L * 1024L * 1024L;
    private static final long MAX_ONE = 8L * 1024L * 1024L;

    private LiteExtensionStore() {}

    static File root(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static List<String> listIds(Context context) {
        File[] dirs = root(context).listFiles(File::isDirectory);
        if (dirs == null) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        for (File dir : dirs) {
            if (new File(dir, "manifest.json").isFile()) out.add(dir.getName());
        }
        Collections.sort(out);
        return out;
    }

    static LiteExtensionManifest readManifest(Context context, String id) {
        try {
            File manifest = new File(new File(root(context), id), "manifest.json");
            if (!manifest.isFile()) return null;
            return LiteExtensionManifest.parse(id, readText(manifest, 2L * 1024L * 1024L));
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", "Lite manifest read failed id=" + id + " :: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    static boolean install(Context context, File packageFile) {
        if (context == null || packageFile == null || !packageFile.isFile()) return false;
        File temp = null;
        try {
            String id = extensionId(packageFile);
            File target = new File(root(context), id);
            temp = new File(root(context), ".tmp-" + id + "-" + System.nanoTime());
            if (!temp.mkdirs()) throw new IOException("cannot create temp extension directory");
            unpackPackage(packageFile, temp);
            File manifest = new File(temp, "manifest.json");
            if (!manifest.isFile()) throw new IOException("manifest.json missing");
            LiteExtensionManifest parsed = LiteExtensionManifest.parse(id,
                    readText(manifest, 2L * 1024L * 1024L));
            if (parsed.manifestVersion < 2 || parsed.manifestVersion > 3) {
                throw new IOException("unsupported manifest_version=" + parsed.manifestVersion);
            }
            if (target.exists() && !deleteRecursively(target)) {
                throw new IOException("cannot replace existing extension");
            }
            if (!temp.renameTo(target)) {
                copyTree(temp, target);
                deleteRecursively(temp);
            }
            RuntimeDiagnostics.event("INFO", "Lite extension installed id=" + id
                    + " name=" + parsed.name + " version=" + parsed.version
                    + " scripts=" + parsed.contentScripts.size());
            return true;
        } catch (Throwable t) {
            RuntimeDiagnostics.event("WARN", "Lite extension install failed :: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (temp != null) deleteRecursively(temp);
            return false;
        }
    }

    static boolean uninstall(Context context, String id) {
        if (context == null || id == null || id.isBlank()) return false;
        File target = new File(root(context), id);
        return !target.exists() || deleteRecursively(target);
    }

    static File extensionDir(Context context, String id) {
        return new File(root(context), id);
    }

    static String readText(File file, long maxBytes) throws IOException {
        if (!file.isFile()) throw new IOException("not a file: " + file);
        if (file.length() > maxBytes) throw new IOException("file too large: " + file.length());
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(buf)) >= 0;) {
                total += n;
                if (total > maxBytes) throw new IOException("file exceeds limit");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void unpackPackage(File input, File target) throws Exception {
        long zipOffset = zipOffset(input);
        try (FileInputStream raw = new FileInputStream(input)) {
            long skipped = 0;
            while (skipped < zipOffset) {
                long n = raw.skip(zipOffset - skipped);
                if (n <= 0) throw new IOException("cannot seek CRX payload");
                skipped += n;
            }
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                int count = 0;
                long total = 0;
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (++count > MAX_FILES) throw new IOException("too many files");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                        throw new IOException("unsafe zip path: " + name);
                    }
                    File out = new File(target, name);
                    String rootPath = target.getCanonicalPath() + File.separator;
                    if (!out.getCanonicalPath().startsWith(rootPath)) {
                        throw new IOException("zip path escapes target");
                    }
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) throw new IOException("mkdir failed");
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("mkdir failed");
                    }
                    long one = 0;
                    try (FileOutputStream fileOut = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = zip.read(buf)) >= 0;) {
                            one += n;
                            total += n;
                            if (one > MAX_ONE || total > MAX_TOTAL) {
                                throw new IOException("extension archive exceeds size limit");
                            }
                            fileOut.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    private static long zipOffset(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[16];
            int n = in.read(head);
            if (n < 4) throw new IOException("empty package");
            if (head[0] == 'P' && head[1] == 'K') return 0L;
            if (n < 12 || head[0] != 'C' || head[1] != 'r' || head[2] != '2' || head[3] != '4') {
                throw new IOException("not CRX/ZIP");
            }
            long version = u32(head, 4);
            if (version == 2) {
                if (n < 16) throw new IOException("invalid CRX2 header");
                long publicKey = u32(head, 8);
                long signature = u32(head, 12);
                return 16L + publicKey + signature;
            }
            if (version == 3) {
                long headerSize = u32(head, 8);
                return 12L + headerSize;
            }
            throw new IOException("unsupported CRX version=" + version);
        }
    }

    private static long u32(byte[] b, int p) {
        return ((long) b[p] & 0xff)
                | (((long) b[p + 1] & 0xff) << 8)
                | (((long) b[p + 2] & 0xff) << 16)
                | (((long) b[p + 3] & 0xff) << 24);
    }

    private static String extensionId(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) >= 0;) digest.update(buf, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            int v = hash[i] & 0xff;
            out.append((char) ('a' + ((v >>> 4) & 0xf)));
            out.append((char) ('a' + (v & 0xf)));
        }
        return out.toString();
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        return file.delete();
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdir failed: " + dst);
            File[] files = src.listFiles();
            if (files != null) for (File file : files) copyTree(file, new File(dst, file.getName()));
            return;
        }
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0;) out.write(buf, 0, n);
        }
    }
}
