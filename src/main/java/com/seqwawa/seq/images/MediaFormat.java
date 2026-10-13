package com.seqwawa.seq.images;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class MediaFormat {
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private MediaFormat() {}

    static String identify(byte[] bytes) throws IOException {
        if (bytes.length < 3 || bytes.length > MAX_BYTES) throw new IOException("Unsupported or oversized media");
        if (bytes.length >= 8
                && bytes[0] == (byte) 137
                && bytes[1] == 80
                && bytes[2] == 78
                && bytes[3] == 71
                && bytes[4] == 13
                && bytes[5] == 10
                && bytes[6] == 26
                && bytes[7] == 10) return "png";
        if (bytes.length >= 6) {
            String signature = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
            if (signature.equals("GIF87a") || signature.equals("GIF89a")) return "gif";
        }
        if (bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) return "jpeg";
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p')
            return "mp4";
        throw new IOException("Unsupported media (use GIF, PNG or JPEG)");
    }
}
