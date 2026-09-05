package com.yagay.chromex;

/**
 * Small compatibility descriptor for verified Chromium builds.
 *
 * <p>Feature implementations are shared. A profile only tells them which exact symbol family is
 * available when stable Chromium APIs are insufficient.</p>
 */
final class ChromiumProfile {
    enum Family {
        CHROME_145,
        CHROME_152
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

    static ChromiumProfile detect(ChromeRuntime runtime) {
        if (runtime == null) return null;
        if (Chrome152.matches(runtime)) {
            return new ChromiumProfile(Family.CHROME_152, runtime.versionName,
                    350L, 500L, 6);
        }
        if (VERIFIED_CHROME145.equals(runtime.versionName)) {
            return new ChromiumProfile(Family.CHROME_145, runtime.versionName,
                    500L, 600L, 6);
        }
        return null;
    }

    boolean is145() {
        return family == Family.CHROME_145;
    }

    boolean is152() {
        return family == Family.CHROME_152;
    }

    String label() {
        return family == Family.CHROME_152 ? "Chrome 152" : "Chrome 145";
    }
}
