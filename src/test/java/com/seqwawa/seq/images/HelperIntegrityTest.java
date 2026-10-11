package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;

class HelperIntegrityTest {
    @TempDir Path temporary;

    @FunctionalInterface
    interface Check {
        void run() throws Exception;
    }

    private void isolatedRoot(Check check) throws Exception {
        Field root = IsolatedDecoder.class.getDeclaredField("extractionRoot");
        root.setAccessible(true);
        Object previous = root.get(null);
        root.set(null, temporary.toRealPath());
        try {
            check.run();
        } finally {
            root.set(null, previous);
        }
    }

    private Path helper() throws Exception {
        String platform =
                IsolatedDecoder.platform(
                        System.getProperty("os.name"), System.getProperty("os.arch"));
        Method resource =
                IsolatedDecoder.class.getDeclaredMethod("resource", String.class, String.class);
        resource.setAccessible(true);
        try {
            return (Path)
                    resource.invoke(
                            null,
                            platform,
                            platform.startsWith("windows-")
                                    ? "seqimages-decoder.exe"
                                    : "seqimages-decoder");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    @Test
    void repeatedUseRejectsSameSizeTampering() throws Exception {
        isolatedRoot(
                () -> {
                    Path file = helper();
                    byte[] bytes = Files.readAllBytes(file);
                    bytes[bytes.length / 2] ^= 1;
                    Files.write(file, bytes);
                    assertEquals(
                            "Sandbox verification failed",
                            assertThrows(IOException.class, this::helper).getMessage());
                });
    }

    @Test
    void repeatedUseRejectsTruncatedHelper() throws Exception {
        isolatedRoot(
                () -> {
                    Path file = helper();
                    Files.write(file, new byte[] {0});
                    assertThrows(IOException.class, this::helper);
                });
    }

    @Test
    void deletedHelperIsRestoredFromBundledBytes() throws Exception {
        isolatedRoot(
                () -> {
                    Path file = helper();
                    byte[] expected = Files.readAllBytes(file);
                    Files.delete(file);
                    assertEquals(file, helper());
                    assertArrayEquals(expected, Files.readAllBytes(file));
                });
    }
}
