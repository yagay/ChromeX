package com.yagay.chromex;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Keeps Chrome's Java download-history view consistent with files normalized by ChromeX.
 *
 * <p>Chrome's native download item may retain the original uniquified target (for example
 * "app (3).apk") even after SameNameOverwriteHooks has transactionally replaced it with
 * "app.apk". The downloads UI is populated later through DownloadManagerService, so we rewrite
 * DownloadInfo before observers see it. During a full list load, an older history item that already
 * points at the same final file is removed from the delivered Java list so the newest download stays
 * at the current position instead of the old entry remaining as the visible one.</p>
 */
final class DownloadHistoryRewriteHooks {
    private static final String DOWNLOAD_ITEM =
            "org.chromium.chrome.browser.download.DownloadItem";

    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    DownloadHistoryRewriteHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        try {
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE);
            Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
        } catch (Throwable t) {
            hooks.warn("download history rewrite unavailable: " + t.getClass().getSimpleName());
            return;
        }

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemCreated", "chromex:history:download-created", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray(), false);
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onDownloadItemUpdated", "chromex:history:download-updated", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray(), false);
                    return chain.proceed();
                });

        hooks.all(runtime.classLoader, Chrome145.DOWNLOAD_MANAGER_SERVICE,
                "onAllDownloadsRetrieved", "chromex:history:download-list", chain -> {
                    if (enabled()) rewriteArguments(chain.getArgs().toArray(), true);
                    return chain.proceed();
                });
    }

    private boolean enabled() {
        return Config.get(prefs, Config.OVERWRITE_DUPLICATE);
    }

    private void rewriteArguments(Object[] args, boolean dedupeLists) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof List<?>) {
                rewriteList((List<?>) arg, dedupeLists);
            } else {
                rewriteObject(arg);
            }
        }
    }

    private void rewriteList(List<?> list, boolean dedupe) {
        if (list == null || list.isEmpty()) return;

        IdentityHashMap<Object, String> rewritten = new IdentityHashMap<>();
        Set<String> rewrittenTargets = new HashSet<>();

        for (Object item : new ArrayList<>(list)) {
            RewriteResult result = rewriteObject(item);
            if (result == null) continue;
            rewritten.put(item, result.targetPath);
            rewrittenTargets.add(result.targetPath);
        }

        if (!dedupe || rewrittenTargets.isEmpty()) return;

        try {
            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                if (rewritten.containsKey(item)) continue;
                Object info = downloadInfoFrom(item);
                String path = infoPath(info);
                String canonical = canonicalPath(path);
                if (canonical != null && rewrittenTargets.contains(canonical)) {
                    iterator.remove();
                    hooks.info("download history deduped older item for " + new File(canonical).getName());
                }
            }
        } catch (Throwable t) {
            hooks.warn("download history list dedupe skipped: " + t.getClass().getSimpleName());
        }
    }

    private RewriteResult rewriteObject(Object value) {
        if (value == null) return null;
        try {
            Class<?> infoType = Reflect.cls(runtime.classLoader, Chrome145.DOWNLOAD_INFO);
            if (infoType.isAssignableFrom(value.getClass())) return rewriteInfo(value);
        } catch (Throwable ignored) {}

        Object info = downloadInfoFrom(value);
        return rewriteInfo(info);
    }

    private Object downloadInfoFrom(Object item) {
        if (item == null) return null;
        try {
            Class<?> itemType = Reflect.cls(runtime.classLoader, DOWNLOAD_ITEM);
            if (!itemType.isAssignableFrom(item.getClass())) return null;
            return Reflect.call(item, "getDownloadInfo");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private RewriteResult rewriteInfo(Object info) {
        if (info == null) return null;

        String path = infoPath(info);
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;

        File oldPath;
        try {
            oldPath = new File(path).getCanonicalFile();
        } catch (Throwable ignored) {
            return null;
        }

        String oldName = oldPath.getName();
        String originalName = DownloadNamePolicy.originalNameFromUniquified(oldName);
        if (originalName == null) {
            String display = infoName(info);
            originalName = DownloadNamePolicy.originalNameFromUniquified(display);
        }
        if (originalName == null) return null;

        File parent = oldPath.getParentFile();
        if (parent == null) return null;
        File target;
        try {
            target = new File(parent, originalName).getCanonicalFile();
        } catch (Throwable ignored) {
            return null;
        }

        // Only rewrite a stale history path produced by our completed filesystem normalization.
        // If the old uniquified file still exists, ChromeX did not replace it and the record must
        // remain untouched. The final original-name file must exist in exactly the same directory.
        if (oldPath.exists() || !target.exists() || !target.isFile()) return null;

        String targetPath = target.getAbsolutePath();
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_PATH, targetPath); } catch (Throwable ignored) {}
        try { Reflect.set(info, Chrome152.DOWNLOAD_INFO_NAME, target.getName()); } catch (Throwable ignored) {}
        try { Reflect.set(info, "f", target.getName()); } catch (Throwable ignored) {}

        hooks.info("download history rewritten: " + oldName + " -> " + target.getName());
        return new RewriteResult(targetPath);
    }

    private String infoPath(Object info) {
        if (info == null) return null;
        try {
            Object value = Reflect.call(info, "getFilePath");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        if (Chrome152.matches(runtime)) {
            try {
                Object value = Reflect.get(info, Chrome152.DOWNLOAD_INFO_PATH);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String infoName(Object info) {
        if (info == null) return null;
        try {
            Object value = Reflect.call(info, "getFileName");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        if (Chrome152.matches(runtime)) {
            try {
                Object value = Reflect.get(info, Chrome152.DOWNLOAD_INFO_NAME);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        try { return new File(path).getCanonicalPath(); }
        catch (Throwable ignored) { return null; }
    }

    private static final class RewriteResult {
        final String targetPath;

        RewriteResult(String targetPath) {
            this.targetPath = targetPath;
        }
    }
}
