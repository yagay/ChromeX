package com.yagay.chromex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Small java.io helper that stays compatible with Android API 31. */
final class IoCompat {
    private IoCompat() {}

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
