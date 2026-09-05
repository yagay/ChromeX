package com.yagay.chromex;

import android.content.SharedPreferences;

import java.lang.reflect.Method;

/** Replaces only an empty adaptive homepage result with the fork's own stored homepage URL. */
final class AdaptiveHomepageValueHooks {
    private final ChromeRuntime runtime;
    private final HookSupport hooks;
    private final SharedPreferences prefs;

    AdaptiveHomepageValueHooks(ChromeRuntime runtime, HookSupport hooks, SharedPreferences prefs) {
        this.runtime = runtime;
        this.hooks = hooks;
        this.prefs = prefs;
    }

    void install() {
        Method getter = AdaptiveDexResolver.resolveHomepageGetter(runtime, hooks);
        if (getter == null) return;
        hooks.method(getter, "chromex:adaptive:homepage-value", chain -> {
            Object direct = chain.proceed();
            if (!Config.get(prefs, Config.CLEAN_START)
                    && !Config.get(prefs, Config.NEWTAB_HOME)) return direct;
            Object resolved = AdaptiveHomepageFallback.fallbackAfterDirect(
                    direct, getter, runtime, hooks);
            if (resolved == direct || !AdaptiveHomepageFallback.usableGurl(resolved)) return direct;
            hooks.info("adaptive empty homepage replaced by vendor preference fallback");
            return resolved;
        });
    }
}
