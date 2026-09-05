package com.yagay.chromex;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Narrow runtime corrections for the verified Chrome 152.0.7977.75 profile.
 *
 * Static DEX analysis established that w5c.e(boolean) is the NTP helper, not the homepage getter.
 * The actual homepage resolver is the instance method w5c.b(boolean, boolean). This layer corrects
 * the already-generated profile without duplicating its entire implementation.
 */
final class Chrome152Corrections {
    private static final long HOME_SETTLE_DELAY_MS = 1800L;

    private final HookSupport hooks;
    private final SharedPreferences prefs;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> handled = Collections.newSetFromMap(new WeakHashMap<>());

    Chrome152Corrections(XposedModule module, HookSupport hooks,
                         SharedPreferences prefs, ClassLoader loader) {
        this.hooks = hooks;
        this.prefs = prefs;
        this.loader = loader;
    }

    void install() {
        installNewTabCorrection();
        installColdStartCorrection();
        installOpenDialogCorrection();
    }

    private void installNewTabCorrection() {
        // iq4#m is the primary ChromeTabCreator path observed on the real 152 device.
        hooks.all(loader, Chrome152.TAB_CREATOR, "m",
                "chromex152:correction:newtab-m", chain -> {
                    if (Config.get(prefs, Config.NEWTAB_HOME) && !chain.getArgs().isEmpty()) {
                        redirectLoadUrlParam(chain.getArg(0));
                    }
                    return chain.proceed();
                });

        // Also cover stable model-side programmatic creation, including the verified profile's own
        // cold-start fallback when it attempts to open an NTP GURL.
        hooks.all(loader, Chrome145.TAB_MODEL, "openTabProgrammatically",
                "chromex152:correction:newtab-model", chain -> {
                    if (!Config.get(prefs, Config.NEWTAB_HOME) || chain.getArgs().isEmpty()) {
                        return chain.proceed();
                    }
                    Object current = chain.getArg(0);
                    if (!isNtp(gurlText(current))) return chain.proceed();
                    Object home = homepageGurl(false, false);
                    if (home == null || isNtp(gurlText(home))) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = home;
                    hooks.info("Chrome 152 corrected model-side new-tab redirect applied");
                    return chain.proceed(args);
                });
    }

    private void redirectLoadUrlParam(Object params) {
        if (params == null) return;
        try {
            if (!Chrome145.LOAD_URL_PARAMS.equals(params.getClass().getName())) return;
            Object raw = Reflect.get(params, Chrome152.LOAD_URL_FIELD);
            if (!(raw instanceof String) || !isNtp((String) raw)) return;
            Object home = homepageGurl(false, false);
            String spec = gurlText(home);
            if (spec == null || spec.isBlank() || isNtp(spec)) return;
            Reflect.set(params, Chrome152.LOAD_URL_FIELD, spec);
            hooks.info("Chrome 152 corrected new-tab redirect applied (custom homepage)");
        } catch (Throwable t) {
            hooks.warn("Chrome 152 corrected new-tab redirect unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private void installColdStartCorrection() {
        hooks.exact(loader, Chrome145.ACTIVITY, "onStart", new Class<?>[0],
                "chromex152:correction:cold-home", chain -> {
                    Object result = chain.proceed();
                    Object receiver = chain.getThisObject();
                    if (!(receiver instanceof Activity) || !Config.get(prefs, Config.CLEAN_START)) {
                        return result;
                    }
                    Activity activity = (Activity) receiver;
                    Intent intent = activity.getIntent();
                    if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                            || intent.getData() != null) return result;
                    synchronized (handled) {
                        if (!handled.add(activity)) return result;
                    }
                    main.postDelayed(() -> settleConfiguredHomepage(activity), HOME_SETTLE_DELAY_MS);
                    return result;
                });
    }

    private void settleConfiguredHomepage(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object home = homepageGurl(false, false);
            String wanted = gurlText(home);
            if (home == null || wanted == null || wanted.isBlank() || isNtp(wanted)) {
                // NTP is a valid user/default configuration. The base 152 profile already handles it.
                return;
            }

            Object selector = selector(activity);
            if (selector == null) return;
            Object regular = Reflect.call(selector, "k", Boolean.FALSE);
            if (regular == null) return;

            ArrayList<Object> models = new ArrayList<>();
            models.add(regular);
            try {
                Object incognito = Reflect.call(selector, "k", Boolean.TRUE);
                if (incognito != null && incognito != regular) models.add(incognito);
            } catch (Throwable ignored) {}

            Object keep = findTab(regular, wanted);
            if (keep == null) {
                keep = Reflect.call(regular, "openTabProgrammatically", home, 2, Boolean.FALSE);
            }
            if (keep == null) return;

            for (Object model : models) closeEverythingExcept(model, keep);
            if (Config.get(prefs, Config.CLEAR_CLOSED_TABS)) clearClosedHistory(activity);
            hooks.info("Chrome 152 corrected cold start settled on configured homepage");
        } catch (Throwable t) {
            hooks.warn("Chrome 152 corrected cold start unavailable: "
                    + t.getClass().getSimpleName());
        }
    }

    private Object selector(Activity activity) {
        try {
            Object direct = Reflect.get(activity, Chrome152.ACTIVITY_SELECTOR_FIELD);
            if (direct != null) return direct;
        } catch (Throwable ignored) {}
        try {
            Class<?> type = Reflect.cls(loader, Chrome152.TAB_SELECTOR);
            return Reflect.findFieldValueByType(activity, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findTab(Object model, String wanted) {
        int count = count(model);
        for (int i = 0; i < count; i++) {
            Object tab = tabAt(model, i);
            if (tab == null) continue;
            try {
                String current = gurlText(Reflect.call(tab, "getUrl"));
                if (wanted.equals(current)) return tab;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void closeEverythingExcept(Object model, Object keep) {
        for (int i = count(model) - 1; i >= 0; i--) {
            Object tab = tabAt(model, i);
            if (tab == null || tab == keep) continue;
            try {
                Class<?> tabType = Reflect.cls(loader, "org.chromium.chrome.browser.tab.Tab");
                Reflect.exact(model.getClass(), "closeTab", tabType).invoke(model, tab);
            } catch (Throwable ignored) {}
        }
    }

    private int count(Object model) {
        try {
            Object value = Reflect.call(model, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Object tabAt(Object model, int index) {
        try {
            return Reflect.call(model, "getTabAt", index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void clearClosedHistory(Activity activity) {
        try {
            Object manager = Reflect.get(activity, Chrome152.ACTIVITY_RECENTLY_CLOSED_FIELD);
            if (manager != null) Reflect.call(manager, "e");
        } catch (Throwable ignored) {}
    }

    private Object homepageGurl(boolean incognito, boolean forZeroTabs) {
        try {
            Class<?> manager = Reflect.cls(loader, Chrome152.HOMEPAGE);
            Object instance = Reflect.callStatic(manager, "d");
            return instance == null ? null : Reflect.call(instance, "b", incognito, forZeroTabs);
        } catch (Throwable t) {
            hooks.warn("Chrome 152 corrected homepage lookup unavailable: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private String gurlText(Object gurl) {
        if (gurl == null) return null;
        try {
            Object value = Reflect.get(gurl, "a");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        for (String method : new String[]{"getSpec", "j"}) {
            try {
                Object value = Reflect.call(gurl, method);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isNtp(String value) {
        return value != null && (value.startsWith("chrome-native://newtab")
                || value.startsWith("chrome://newtab"));
    }

    private void installOpenDialogCorrection() {
        // Verified DEX signature: J.N.VJOZ(int, long, String, boolean), selector 9 + true.
        hooks.all(loader,
                "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                "showDialog", "chromex152:correction:open-dialog", chain -> {
                    if (!Config.get(prefs, Config.BYPASS_OPEN)) return chain.proceed();
                    String path = lastString(chain.getArgs().toArray());
                    if (path == null) return chain.proceed();
                    try {
                        long ptr = nativePtr(chain.getThisObject());
                        if (ptr == 0L) return chain.proceed();
                        Class<?> nativeClass = Reflect.cls(loader, Chrome145.NATIVE);
                        Method callback = Reflect.exact(nativeClass, "VJOZ",
                                int.class, long.class, String.class, boolean.class);
                        callback.invoke(null, Chrome152.OPEN_ACCEPT, ptr, path, true);
                        hooks.info("Chrome 152 open-file confirmation accepted via corrected signature");
                        return null;
                    } catch (Throwable t) {
                        hooks.error("Chrome 152 corrected open-file callback", t);
                        return chain.proceed();
                    }
                });
    }

    private long nativePtr(Object bridge) {
        if (bridge == null) return 0L;
        try {
            long value = Reflect.getLong(bridge, "a");
            if (value != 0L) return value;
        } catch (Throwable ignored) {}

        Field found = null;
        Class<?> type = bridge.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                if (found != null) return 0L;
                field.setAccessible(true);
                found = field;
            }
            type = type.getSuperclass();
        }
        if (found == null) return 0L;
        try {
            return found.getLong(bridge);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String lastString(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }
}
