package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URI;

class NativePlatformTest {
    @Test
    void selectsNativeHelperForEachSupportedJvm() throws Exception {
        for (String[] pair :
                new String[][] {
                    {"Windows 11", "amd64", "windows-x86_64"},
                    {"Windows 11", "aarch64", "windows-aarch64"},
                    {"Linux", "x86_64", "linux-x86_64"},
                    {"Linux", "arm64", "linux-aarch64"},
                    {"Mac OS X", "x86_64", "macos-x86_64"},
                    {"Mac OS X", "aarch64", "macos-aarch64"}
                }) assertEquals(pair[2], IsolatedDecoder.platform(pair[0], pair[1]));
    }

    @Test
    void rejectsUnsupportedPlatformWithoutStartingHelper() {
        assertThrows(IOException.class, () -> IsolatedDecoder.platform("Linux", "arm"));
        assertThrows(IOException.class, () -> IsolatedDecoder.platform("Other", "amd64"));
    }

    @Test
    void malformedHostlessInputsFailWithControlledError() {
        for (String url :
                new String[] {
                    "file:///tracking.gif",
                    "data:image/gif;base64,R0lGODlh",
                    "https://cdn.discordapp.com%2elogger.invalid/tracking.gif"
                }) assertThrows(IOException.class, () -> MediaDownload.fetch(URI.create(url)));
    }

    @Test
    void pixelProtocolHandlesShortReadsAndPreservesEveryArgbBit() throws Exception {
        var raw = new ByteArrayOutputStream();
        var out = new DataOutputStream(raw);
        for (int v : new int[] {0x53494d31, 2, 1, 1, 55, 0x00123456, 0x80abcdef}) out.writeInt(v);
        var input =
                new FilterInputStream(new ByteArrayInputStream(raw.toByteArray())) {
                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return super.read(b, off, Math.min(len, 1));
                    }
                };
        var a = DecodedFrames.read(input);
        assertEquals(55, a.frames().getFirst().durationMs());
        assertArrayEquals(new int[] {0x00123456, 0x80abcdef}, a.frames().getFirst().argb());
    }
}
