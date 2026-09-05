package com.yagay.chromex;

/**
 * Small compatibility descriptor for Chromium feature code.
 *
 * <p>Verified builds expose exact fallback symbols. Unknown Chromium builds use the ADAPTIVE
 * family, which is restricted to stable APIs, structural reflection and DexKit-resolved symbols.</p>
 */
final class ChromiumProfile {
    enum Family {
        CHROME_145,
        CHROME_152,
        ADAPTIVE
    }

    static final String VERIFIED_CHROME145 = "145.0.7632.218";

    final Family family;
    final String versionName;
    final long coldDelayMs;
    final long retryDelayMs;
    final int maxRounds;

    private ChromiumProfile(Family family, String versionName,
                            long coldDelayMs, long retryDelayMs, int maxRounds) {
        this.family = family;
        this.versionName = versionName;
        this.coldDelayMs = coldDelayMs;
        this.retryDelayMs = retryDelayMs;
        this.maxRounds = maxRounds;
    }

    static ChromiumProfile resolve(ChromeRuntime runtime) {
        if (runtime == null) return null;
        if (Chrome152.matches(runtime)) {
            return new ChromiumProfile(Family.CHROME_152, runtime.versionName,
                    350L, 500L, 6);
        }
        if (VERIFIED_CHROME145.equals(runtime.versionName)) {
            return new ChromiumProfile(Family.CHROME_145, runtime.versionName,
                    500L, 600L, 6);
        }
        return new ChromiumProfile(Family.ADAPTIVE, runtime.versionName,
                700L, 800L, 5);
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
        return "Adaptive Chromium";
    }
}
