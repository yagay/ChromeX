package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ChromiumEngineVersionScannerTest {
    @Test
    public void selectsChromiumBuildOverLoopbackLiteral() {
        byte[] bytes = ("noise 127.0.0.1 more 127.0.6533.144 end")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("127.0.6533.144", ChromiumEngineVersionScanner.bestInBytes(bytes));
    }

    @Test
    public void rejectsApplicationVersionAndLoopback() {
        assertFalse(ChromiumEngineVersionScanner.plausible("2.7.3.019"));
        assertFalse(ChromiumEngineVersionScanner.plausible("127.0.0.1"));
        assertTrue(ChromiumEngineVersionScanner.plausible("127.0.6533.144"));
    }
}
