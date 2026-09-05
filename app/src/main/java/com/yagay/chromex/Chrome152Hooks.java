package com.yagay.chromex;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

/**
 * Chrome 152 profile verified directly against Chrome 152.0.7977.75 split_chrome.apk.
 * Short R8 symbols and J.N selectors in this class must never be used for other Chrome majors.
 */
final class Chrome152Hooks {
    private static final long COLD_DELAY_MS = 1200L;
    private static final long RETRY_DELAY_MS = 1200L;
    private static final int MAX_COLD_ROUNDS = 4;
    private static final long BANNER_WINDOW_MS = 3000L;
    private static final long TOAST_DEDUP_MS = 3000L;
    private static final long INSTALL_DEDUP_MS = 90_000L;
    private static final long INSTALL_WAIT_MS = 60_000L;
    private static final long INSTALL_POLL_MS = 500L;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final XposedModule module;
    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Object>> models = new ArrayList<>();
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());
    private final AtomicLong suppressBannerUntil = new AtomicLong(0L);
    private final AtomicLong lastToastAt = new AtomicLong(0L);
    private final AtomicReference<String> lastToastName = new AtomicReference<>("");
    private final AtomicReference<InstallStamp> lastInstall = new AtomicReference<>();
    private final ExecutorService installerWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChromeX-152-apk-installer");
        t.setDaemon(true);
        return t;
    });

    Chrome152Hooks(XposedModule module, HookSupport hooks,
                   SharedPreferences prefs, ClassLoader loader) {
        this.module = module;
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        hooks.info("Chrome 152 verified profile active: " + ChromeVersion.name());
        installNoRestore();
        installModelCapture();
        installActivityLifecycle();
        installNewTabRedirects();
        installDownloadDialogs();
        installDownloadCompletion();
        installDownloadMessageSuppression();
        installTranslateSuppression();
    }

    // ------------------------------------------------------------------------
    // Tabs / homepage
    // ------------------------------------------------------------------------

    private void installNoRestore() {
        hooks.exact(loader, "org.chromium.base.CommandLine", "c",
                new Class<?>[]{String.class}, "chromex152:tabs:no-restore", chain -> {
                    if (Config.get(prefs, Config.CLEAN_START)
                            && "no-restore-state".equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
    }

    private void installModelCapture() {
        hooks.exact(loader, Chrome145.TAB_MODEL, "getCount", new Class<?>[0],
                "chromex152:tabs:model", chain -> {
                    Object result = chain.proceed();
                    remember(chain.getThisObject());
                    return result;
                });
    }

    private void installActivityLifecycle() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex152:tabs:onStart", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof Activity) {
                        Activity activity = (Activity) receiver;
                        captureSelectorModels(activity);
                        scheduleColdStart(activity);
                    }
                    return result;
                });

        hooks.exact(loader, Chrome145.ACTIVITY, "onDestroy", new Class<?>[0],
                "chromex152:tabs:onDestroy", chain -> {
                    Activity activity = chain.getThisObject() instanceof Activity
                            ? (Activity) chain.getThisObject() : null;
                    if (activity != null && activity.isFinishing()
                            && Config.get(prefs, Config.CLEAR_CLOSED_TABS)) {
                        try {
                            captureSelectorModels(activity);
                            closeAllKnownTabs();
                            clearClosedHistory(activity);
                        } catch (Throwable t) {
                            hooks.error("Chrome 152 exit cleanup", t);
                        }
                    }
                    return chain.proceed();
                });
    }

    private void installNewTabRedirects() {
        // iq4 is ChromeTabCreator in 152. Hook every verified LoadUrlParams entry point because
        // different launch sources enter at different depths of the creator.
        for (String method : new String[]{"a", "f", "j", "l", "m"}) {
            hooks.all(loader, Chrome152.TAB_CREATOR, method,
                    "chromex152:tabs:creator:" + method, chain -> {
                        redirectLoadUrlParam(chain.getArgs().isEmpty() ? null : chain.getArg(0));
                        return chain.proceed();
                    });
        }

        // k3r.B is a selector-side tab creation path that can bypass iq4 for some sources.
        hooks.all(loader, Chrome152.TAB_SELECTOR, "B",
                "chromex152:tabs:selector-create", chain -> {
                    redirectLoadUrlParam(chain.getArgs().isEmpty() ? null : chain.getArg(0));
                    return chain.proceed();
                });
    }

    private void redirectLoadUrlParam(Object params) {
        if (!Config.get(prefs, Config.NEWTAB_HOME) || params == null) return;
        try {
            if (!Chrome145.LOAD_URL_PARAMS.equals(params.getClass().getName())) return;
            Object raw = Reflect.get(params, Chrome152.LOAD_URL_FIELD);
            if (!(raw instanceof String) || !isNtp((String) raw)) return;
            String home = resolveHomeUrl();
            if (home == null || home.isBlank() || isNtp(home)) return;
            Reflect.set(params, Chrome152.LOAD_URL_FIELD, home);
            hooks.info("Chrome 152 new-tab URL redirected to homepage");
        } catch (Throwable t) {
            hooks.warn("Chrome 152 LoadUrlParams redirect unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private void scheduleColdStart(Activity activity) {
        if (!Config.get(prefs, Config.CLEAN_START)) return;
        Intent intent = activity.getIntent();
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || intent.getData() != null) return;
        synchronized (handled) {
            if (!handled.add(activity)) return;
        }
        main.postDelayed(() -> coldRound(activity, 0), COLD_DELAY_MS);
    }

    private void coldRound(Activity activity, int round) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            captureSelectorModels(activity);
            Object regular = findRegularModel(activity);
            if (regular == null) {
                retryCold(activity, round);
                return;
            }
            String home = resolveHomeUrl();
            Object keep = findExactHomeTab(regular, home);
            if (keep == null) keep = openHomeTab(regular, home);
            if (keep == null) {
                retryCold(activity, round);
                return;
            }
            closeEverythingExcept(keep);
            if (count(regular) <= 1 || round + 1 >= MAX_COLD_ROUNDS) {
                if (Config.get(prefs, Config.CLEAR_CLOSED_TABS)) clearClosedHistory(activity);
                hooks.info("Chrome 152 cold start settled at round " + round);
            } else {
                retryCold(activity, round);
            }
        } catch (Throwable t) {
            hooks.error("Chrome 152 cold round " + round, t);
            retryCold(activity, round);
        }
    }

    private void retryCold(Activity activity, int round) {
        if (round + 1 >= MAX_COLD_ROUNDS) return;
        main.postDelayed(() -> coldRound(activity, round + 1), RETRY_DELAY_MS);
    }

    private void captureSelectorModels(Activity activity) {
        try {
            Object selector = selector(activity);
            if (selector == null) return;
            for (boolean incognito : new boolean[]{false, true}) {
                try {
                    Object model = Reflect.call(selector, "k", incognito);
                    if (model != null) remember(model);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private Object selector(Activity activity) {
        try {
            Object direct = Reflect.get(activity, Chrome152.ACTIVITY_SELECTOR_FIELD);
            if (direct != null && Chrome152.TAB_SELECTOR.equals(direct.getClass().getName())) {
                return direct;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> type = Reflect.cls(loader, Chrome152.TAB_SELECTOR);
            return Reflect.findFieldValueByType(activity, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findRegularModel(Activity activity) {
        Object selector = selector(activity);
        if (selector != null) {
            try {
                Object model = Reflect.call(selector, "k", Boolean.FALSE);
                if (model != null) {
                    remember(model);
                    return model;
                }
            } catch (Throwable ignored) {}
        }
        for (Object model : knownModels()) {
            if (!incognito(model)) return model;
        }
        return null;
    }

    private String resolveHomeUrl() {
        try {
            Class<?> manager = Reflect.cls(loader, Chrome152.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            Object gurl = instance == null ? null : Reflect.call(instance, "e", Boolean.FALSE);
            String value = gurlText(gurl);
            return value == null || value.isBlank() ? Chrome145.NTP : value;
        } catch (Throwable t) {
            hooks.warn("Chrome 152 homepage lookup unavailable: "
                    + t.getClass().getSimpleName());
            return Chrome145.NTP;
        }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        try {
            Object value = Reflect.get(gurl, "a");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        for (String method : new String[]{"j", "e", "g"}) {
            try {
                Object value = Reflect.call(gurl, method);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }

    private Object findExactHomeTab(Object model, String home) {
        int total = count(model);
        for (int i = 0; i < total; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String url = gurlText(Reflect.call(tab, "getUrl"));
                if (home == null || home.isBlank() || isNtp(home)) {
                    if (isNtp(url)) return tab;
                } else if (home.equals(url)) {
                    return tab;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object openHomeTab(Object model, String home) {
        String url = home == null || home.isBlank() ? Chrome145.NTP : home;
        try {
            Object gurl = Reflect.construct(Reflect.cls(loader, Chrome145.GURL), url);
            Object tab = Reflect.call(model, "openTabProgrammatically", gurl, 2, Boolean.FALSE);
            if (tab != null) return tab;
        } catch (Throwable ignored) {}
        try {
            Class<?> gurlType = Reflect.cls(loader, Chrome145.GURL);
            Object gurl = Reflect.construct(gurlType, url);
            for (Method method : model.getClass().getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[1] != int.class || method.getReturnType() == void.class) continue;
                if (!p[0].isAssignableFrom(gurlType) && !gurlType.isAssignableFrom(p[0])) continue;
                method.setAccessible(true);
                Object value = method.invoke(model, gurl, 2);
                if (value != null) return value;
            }
        } catch (Throwable t) {
            hooks.warn("Chrome 152 open-home fallback unavailable: "
                    + t.getClass().getSimpleName());
        }
        return null;
    }

    private void remember(Object model) {
        if (model == null) return;
        synchronized (models) {
            Iterator<WeakReference<Object>> it = models.iterator();
            while (it.hasNext()) {
                Object value = it.next().get();
                if (value == null) it.remove();
                else if (value == model) return;
            }
            models.add(new WeakReference<>(model));
        }
    }

    private List<Object> knownModels() {
        ArrayList<Object> result = new ArrayList<>();
        synchronized (models) {
            Iterator<WeakReference<Object>> it = models.iterator();
            while (it.hasNext()) {
                Object value = it.next().get();
                if (value == null) it.remove();
                else result.add(value);
            }
        }
        return result;
    }

    private int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean incognito(Object model) {
        try {
            Object value = Reflect.call(model, "isIncognito");
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private Object tabAt(Object model, int index) {
        try {
            return Reflect.call(model, "getTabAt", index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void closeEverythingExcept(Object keep) {
        for (Object model : knownModels()) {
            for (int i = count(model) - 1; i >= 0; i--) {
                Object tab = tabAt(model, i);
                if (tab != null && tab != keep) closeTab(model, tab);
            }
        }
    }

    private void closeAllKnownTabs() {
        for (Object model : knownModels()) {
            try {
                Reflect.call(model, "forceCloseAllTabs");
                continue;
            } catch (Throwable ignored) {}
            for (int i = count(model) - 1; i >= 0; i--) {
                Object tab = tabAt(model, i);
                if (tab != null) closeTab(model, tab);
            }
        }
    }

    private void closeTab(Object model, Object tab) {
        try {
            Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
            Method method = Reflect.exact(model.getClass(), "closeTab", tabType);
            method.invoke(model, tab);
        } catch (Throwable ignored) {}
    }

    private void clearClosedHistory(Activity activity) {
        try {
            Object manager = Reflect.get(activity, Chrome152.ACTIVITY_RECENTLY_CLOSED_FIELD);
            if (manager == null) return;
            // Chrome 152 R8: RecentlyClosedEntriesManager.clearRecentlyClosedEntries() -> e().
            Reflect.call(manager, "e");
            hooks.info("Chrome 152 recently-closed history cleared");
        } catch (Throwable t) {
            hooks.warn("Chrome 152 recently-closed clear unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------------
    // Download confirmation dialogs
    // ------------------------------------------------------------------------

    private void installDownloadDialogs() {
        hookDangerous();
        hookInsecure();
        hookDuplicate();
        hookPolicy();
        hookLocation();
        hookOpen();
    }

    private void hookDangerous() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                "showDialog", "chromex152:download:dangerous", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DANGEROUS)) return chain.proceed();
                    if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                        return chain.proceed();
                    }
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                Chrome152.DANGEROUS_ACCEPT, ptr, chain.getArg(1));
                        hooks.info("Chrome 152 dangerous download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 dangerous confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookInsecure() {
        hooks.all(loader, "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                "showDialog", "chromex152:download:insecure", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_INSECURE)) return chain.proceed();
                    if (chain.getArgs().size() < 4 || !(chain.getArg(3) instanceof Number)) {
                        return chain.proceed();
                    }
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        long callback = ((Number) chain.getArg(3)).longValue();
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJJZ",
                                new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                Chrome152.INSECURE_ACCEPT, ptr, callback, true);
                        hooks.info("Chrome 152 insecure download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 insecure confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookDuplicate() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                "showDialog", "chromex152:download:duplicate", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_DUPLICATE)) return chain.proceed();
                    if (chain.getArgs().isEmpty()) return chain.proceed();
                    Object last = chain.getArg(chain.getArgs().size() - 1);
                    if (!(last instanceof Number)) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        long callback = ((Number) last).longValue();
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJJZ",
                                new Class<?>[]{int.class, long.class, long.class, boolean.class},
                                Chrome152.DUPLICATE_ACCEPT, ptr, callback, true);
                        hooks.info("Chrome 152 duplicate download confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 duplicate confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookPolicy() {
        hooks.all(loader, "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                "showDialog", "chromex152:download:policy", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_POLICY)) return chain.proceed();
                    if (chain.getArgs().size() < 2 || !(chain.getArg(1) instanceof String)) {
                        return chain.proceed();
                    }
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJO", new Class<?>[]{int.class, long.class, Object.class},
                                Chrome152.POLICY_ACCEPT, ptr, chain.getArg(1));
                        hooks.info("Chrome 152 policy warning confirmed automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 policy confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookLocation() {
        hooks.all(loader, "org.chromium.chrome.browser.download.DownloadDialogBridge",
                "showDialog", "chromex152:download:location", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_LOCATION)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) path = "";
                    try {
                        Reflect.call(chain.getThisObject(), "b", path, Boolean.FALSE);
                        hooks.info("Chrome 152 download location accepted automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 location confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private void hookOpen() {
        hooks.all(loader, "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex152:download:open", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        nativeCall("VJOZ",
                                new Class<?>[]{int.class, long.class, Object.class, boolean.class},
                                Chrome152.OPEN_ACCEPT, ptr, path, true);
                        hooks.info("Chrome 152 open-file confirmation accepted automatically");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 open-file confirmation", t);
                        return chain.proceed();
                    }
                });
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            return Reflect.getLong(bridge, "a");
        } catch (Throwable ignored) {}
        Field found = null;
        Class<?> c = bridge.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                if (found != null) return 0L;
                field.setAccessible(true);
                found = field;
            }
            c = c.getSuperclass();
        }
        if (found == null) return 0L;
        try {
            return found.getLong(bridge);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private Object nativeCall(String name, Class<?>[] params, Object... args)
            throws ReflectiveOperationException {
        Class<?> n = Reflect.cls(loader, Chrome145.NATIVE);
        Method method = Reflect.exact(n, name, params);
        return method.invoke(null, args);
    }

    // ------------------------------------------------------------------------
    // Download completion / APK installer / banner replacement
    // ------------------------------------------------------------------------

    private void installDownloadCompletion() {
        hooks.all(loader, Chrome145.DOWNLOAD_CONTROLLER, "onDownloadCompleted",
                "chromex152:download:completed", chain -> {
                    Object info = findDownloadInfo(chain.getArgs().toArray());
                    handleDownloadCompletion(info);
                    return chain.proceed();
                });

        hooks.all(loader, Chrome145.DOWNLOAD_UTILS, "openDownload",
                "chromex152:download:open-apk", chain -> {
                    if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (args.length < 2) return chain.proceed();
                    String path = asString(args[0]);
                    String mime = asString(args[1]);
                    String name = args.length == 0 ? null : asString(args[args.length - 1]);
                    if (!isApk(mime, name != null ? name : path)) return chain.proceed();
                    try {
                        String fileName = normalizedName(name, path);
                        Uri uri = resolveUri(path, fileName);
                        if (uri != null && launchInstaller(uri,
                                fileName == null ? uri.toString() : fileName)) {
                            return null;
                        }
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 openDownload APK", t);
                    }
                    return chain.proceed();
                });
    }

    private Object findDownloadInfo(Object[] args) {
        try {
            Class<?> type = Reflect.cls(loader, Chrome145.DOWNLOAD_INFO);
            for (Object arg : args) {
                if (arg != null && type.isAssignableFrom(arg.getClass())) return arg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void handleDownloadCompletion(Object info) {
        if (info == null) return;
        try {
            String mime = stringField(info, Chrome152.DOWNLOAD_INFO_MIME);
            String name = stringField(info, Chrome152.DOWNLOAD_INFO_NAME);
            String path = stringField(info, Chrome152.DOWNLOAD_INFO_PATH);
            boolean apk = isApk(mime, name != null ? name : path);

            boolean replaceBanner = Config.get(prefs, Config.ALL_DOWNLOAD_TOAST)
                    || (apk && Config.get(prefs, Config.APK_TOAST));
            if (replaceBanner) {
                suppressBannerUntil.set(System.currentTimeMillis() + BANNER_WINDOW_MS);
                showToastOnce(fileName(path, name));
            }

            if (apk && Config.get(prefs, Config.AUTO_INSTALL_APK)) {
                enqueueInstall(path, name);
            }
        } catch (Throwable t) {
            hooks.error("Chrome 152 DownloadInfo completion", t);
        }
    }

    private void installDownloadMessageSuppression() {
        // ia8 is DownloadMessageUiControllerImpl in Chrome 152.0.7977.75.
        hooks.all(loader, Chrome152.DOWNLOAD_MESSAGE, "d",
                "chromex152:banner:message-d", chain -> {
                    if (suppressBannerUntil.get() >= System.currentTimeMillis()) return null;
                    return chain.proceed();
                });
        hooks.all(loader, Chrome152.DOWNLOAD_MESSAGE, "a",
                "chromex152:banner:message-a", chain -> {
                    if (suppressBannerUntil.get() >= System.currentTimeMillis()) return null;
                    return chain.proceed();
                });
    }

    private void installTranslateSuppression() {
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "create",
                "chromex152:translate:create", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
        hooks.all(loader, Chrome145.TRANSLATE_MESSAGE, "showMessage",
                "chromex152:translate:show", chain -> {
                    if (Config.get(prefs, Config.HIDE_TRANSLATE)) return null;
                    return chain.proceed();
                });
    }

    private void enqueueInstall(String path, String name) {
        String fileName = normalizedName(name, path);
        if (fileName == null) return;
        installerWorker.execute(() -> {
            long deadline = System.currentTimeMillis() + INSTALL_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!Config.get(prefs, Config.AUTO_INSTALL_APK)) return;
                try {
                    Uri uri = resolveUri(path, fileName);
                    if (uri != null && launchInstaller(uri, fileName)) return;
                } catch (Throwable t) {
                    hooks.error("Chrome 152 APK polling", t);
                }
                try {
                    Thread.sleep(INSTALL_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            hooks.warn("Chrome 152 APK not resolvable after 60s: " + fileName);
        });
    }

    private Uri resolveUri(String raw, String fileName) {
        if (raw != null && raw.startsWith("content://")) return Uri.parse(raw);
        if (raw != null && raw.startsWith("/")) {
            try {
                Uri value = chromeContentUri(raw);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }
        return fileName == null ? null : mediaStoreUri(fileName);
    }

    private Uri chromeContentUri(String path) {
        try {
            Class<?> utils = Reflect.cls(loader, Chrome145.DOWNLOAD_UTILS);
            Object value = Reflect.exact(utils, "e", String.class).invoke(null, path);
            return value instanceof Uri ? (Uri) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Uri mediaStoreUri(String fileName) {
        Context context = chromeContext();
        if (context == null) return null;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Files.getContentUri("external");
            cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{fileName},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private boolean launchInstaller(Uri uri, String key) {
        long now = System.currentTimeMillis();
        InstallStamp previous = lastInstall.get();
        if (previous != null && previous.key.equals(key)
                && now - previous.time < INSTALL_DEDUP_MS) return true;
        Context context = chromeContext();
        if (context == null) return false;
        InstallStamp next = new InstallStamp(key, now);
        lastInstall.set(next);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            hooks.info("Chrome 152 APK installer launched: " + key);
            return true;
        } catch (Throwable t) {
            lastInstall.compareAndSet(next, previous);
            hooks.error("Chrome 152 launch APK installer", t);
            return false;
        }
    }

    private void showToastOnce(String name) {
        long now = System.currentTimeMillis();
        String safe = name == null || name.isBlank() ? "下载文件" : name;
        if (safe.equals(lastToastName.get()) && now - lastToastAt.get() < TOAST_DEDUP_MS) return;
        lastToastName.set(safe);
        lastToastAt.set(now);
        Context context = chromeContext();
        if (context == null) return;
        main.post(() -> {
            try {
                Toast.makeText(context, "下载完成: " + safe, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                hooks.error("Chrome 152 download Toast", t);
            }
        });
    }

    private static boolean isApk(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("package-archive")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static String normalizedName(String name, String path) {
        String value = name;
        if (value == null || value.isBlank()) value = path;
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("content://")) return value;
        try {
            return new File(value).getName();
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String fileName(String path, String fallback) {
        String value = path;
        if (value == null || value.isBlank()) value = fallback;
        if (value == null || value.isBlank()) return "下载文件";
        try {
            if (!value.startsWith("content://")) value = new File(value).getName();
        } catch (Throwable ignored) {}
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        return value.isBlank() ? "下载文件" : value;
    }

    private static String stringField(Object owner, String name) {
        try {
            Object value = Reflect.get(owner, name);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }

    private static Context chromeContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class InstallStamp {
        final String key;
        final long time;

        InstallStamp(String key, long time) {
            this.key = key;
            this.time = time;
        }
    }
}
