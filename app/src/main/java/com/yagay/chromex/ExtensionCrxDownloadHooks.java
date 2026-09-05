package com.yagay.chromex;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Routes completed .crx downloads into the currently selected extension backend. */
final class ExtensionCrxDownloadHooks {
    private static final long SETTLE_MS = 1000L;
    private static final long DEDUP_MS = 15_000L;

    private final ChromiumProfile profile;
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> handled = new ConcurrentHashMap<>();

    ExtensionCrxDownloadHooks(ChromiumProfile profile, ChromeRuntime runtime, HookSupport hooks) {
        this.profile = profile;
        this.runtime = runtime;
        this.hooks = hooks;
    }

    void install() {
        hookOwner(Chrome145.DOWNLOAD_CONTROLLER, "chromex:plus:crx:controller");
        hookOwner(Chrome145.DOWNLOAD_MANAGER_SERVICE, "chromex:plus:crx:manager");
    }

    private void hookOwner(String className, String id) {
        try {
            Class<?> type = Reflect.cls(runtime.classLoader, className);
            boolean found = false;
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("onDownloadCompleted")) continue;
                found = true;
                hooks.method(method, id + ":" + method.getParameterCount(), chain -> {
                    Object info = DownloadInfoAccessor.find(chain.getArgs().toArray(), runtime.classLoader);
                    Object result = chain.proceed();
                    if (info != null) main.postDelayed(() -> handle(info), SETTLE_MS);
                    return result;
                });
            }
            if (found) hooks.info("CRX completion bridge attached: " + className);
        } catch (Throwable ignored) {}
    }

    private void handle(Object info) {
        try {
            DownloadInfoAccessor.Values values = DownloadInfoAccessor.read(info, profile);
            String nameOrPath = values.name != null ? values.name : values.path;
            if (!ExtensionPackagePolicy.isCrx(values.mime, nameOrPath)) return;
            if (values.path == null || values.path.isBlank()) {
                hooks.warn("CRX download has no resolved path: " + nameOrPath);
                return;
            }
            File file = new File(values.path);
            if (!file.isFile()) {
                String logical = DownloadNormalizationRegistry.logicalPath(values.path);
                if (logical != null) file = new File(logical);
            }
            if (!file.isFile()) {
                hooks.warn("CRX download path not ready: " + values.path);
                return;
            }
            String key = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
            long now = System.currentTimeMillis();
            Long previous = handled.put(key, now);
            if (previous != null && now - previous < DEDUP_MS) return;
            handled.entrySet().removeIf(e -> now - e.getValue() > DEDUP_MS * 4);

            ExtensionBackend backend = ExtensionRuntimeRegistry.backend();
            if (backend == null || !backend.isAvailable()) {
                hooks.warn("CRX downloaded but extension backend is unavailable: " + file.getName());
                return;
            }
            boolean ok = backend.installCrx(file);
            String message = ok
                    ? "扩展已安装: " + file.getName()
                    : "扩展安装失败: " + file.getName();
            hooks.info("CRX install result=" + ok + " backend="
                    + backend.getClass().getSimpleName() + " file=" + file.getName());
            if (runtime.application != null) {
                Toast.makeText(runtime.application, message,
                        ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            hooks.error("CRX download install", t);
        }
    }
}
