package com.yagay.chromex;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.Test;

public final class ExtensionCapabilityDetectorTest {
    @Test
    public void streamingMarkerScannerFindsMarkersAcrossBufferBoundary() throws Exception {
        File file = File.createTempFile("chromex-native-scan", ".bin");
        try {
            byte[] padding = new byte[1024 * 1024 - 5];
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(padding);
                out.write("ExtensionSystemImpl".getBytes(StandardCharsets.UTF_8));
                out.write(new byte[4096]);
                out.write("chrome-extension://".getBytes(StandardCharsets.UTF_8));
            }
            Set<String> found = ExtensionCapabilityDetector.scanMarkers(file,
                    new String[]{"ExtensionSystemImpl", "chrome-extension://", "MissingMarker"});
            assertTrue(found.contains("ExtensionSystemImpl"));
            assertTrue(found.contains("chrome-extension://"));
            assertTrue(!found.contains("MissingMarker"));
        } finally {
            file.delete();
        }
    }
}
