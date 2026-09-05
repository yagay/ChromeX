package com.yagay.chromex;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DexNativeSelectorResolverTest {
    @Test
    public void rejectsMissingInputs() {
        assertNull(DexNativeSelectorResolver.resolve(null, "a", "b"));
        assertNull(DexNativeSelectorResolver.resolve("/not/a/file.apk", "a", "b"));
        assertNull(DexNativeSelectorResolver.resolve("/not/a/file.apk", null, "b"));
        assertNull(DexNativeSelectorResolver.resolve("/not/a/file.apk", "a", null));
    }
}
