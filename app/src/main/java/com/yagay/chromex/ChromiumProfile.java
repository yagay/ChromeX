package com.yagay.chromex;

/**
 * Compatibility descriptor for Chromium feature code.
 *
 * <p>Verified Chrome builds expose exact fallback symbols. Unknown Chromium builds use the
 * ADAPTIVE family and resolve their engine version and capabilities structurally at runtime.</p>
 */
final class ChromiumProfile {
    enum Family {
        CHROME_145,
        CHROME_152,
        ADAPTIVE
    }

    static final String VERIFIED_CHROME145 = "145.0.7632.218";

    final Family family;
    /** Application/package version (for example Lemur 2.7.3.019). */
    final String versionName;
    /** Actual Chromium product version when it can be resolved (for example 127.0.6533.144). */
    final String engineVersion;
    final int engineMajorVersion;
    final long coldDelayMs;
    final long retryDelayMs;
    final int maxRounds;

    private ChromiumProfile(Family family, String versionName, String engineVersion,
                            long coldDelayMs, long retryDelayMs, int maxRounds) {
        this.family = family;
        this.versionName = versionName;
        this.engineVersion = engineVersion == null || engineVersion.isBlank()
                ? "unknown" : engineVersion;
        this.engineMajorVersion = parseMajor(this.engineVersion);
        this.coldDelayMs = coldDelayMs;
        this.retryDelayMs = retryDelayMs;
        this.maxRounds = maxRounds;
    }

    static ChromiumProfile resolve(ChromeRuntime runtime, HookSupport hooks) {
        if (runtime == null) return null;
        if (Chrome152.matches(runtime)) {
            return new ChromiumProfile(Family.CHROME_152, runtime.versionName, runtime.versionName,
                    350L, 500L, 6);
        }
        if (VERIFIED_CHROME145.equals(runtime.versionName)) {
            return new ChromiumProfile(Family.CHROME_145, runtime.versionName, runtime.versionName,
                    500L, 600L, 6);
        }
        String engine = hooks == null
                ? runtime.versionName : AdaptiveDexResolver.resolveProductVersion(runtime, hooks);
        return new ChromiumProfile(Family.ADAPTIVE, runtime.versionName, engine,
                700L, 800L, 6);
    }

    boolean is145() {
        return family == Family.CHROME_145;
    }

    boolean is152() {
        return family == Family.CHROME_152;
    }

    boolean isAdaptive() {
        return family == Family.ADAPTIVE;
    }

    boolean isVerifiedExact() {
        return !isAdaptive();
    }

    String label() {
        if (is152()) return "Chrome 152";
        if (is145()) return "Chrome 145";
        return engineMajorVersion > 0
                ? "Adaptive Chromium " + engineMajorVersion
                : "Adaptive Chromium";
    }

    private static int parseMajor(String value) {
        if (value == null || value.isBlank()) return -1;
        int dot = value.indexOf('.');
        String head = dot < 0 ? value : value.substring(0, dot);
        try { return Integer.parseInt(head); }
        catch (Throwable ignored) { return -1; }
    }
}
