package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelperIntegrityTest {
    @TempDir
    Path temporary;

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
        String platform = IsolatedDecoder.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Method resource = IsolatedDecoder.class.getDeclaredMethod("resource", String.class, String.class);
        resource.setAccessible(true);
        try {
            return (Path) resource.invoke(
                    null, platform, platform.startsWith("windows-") ? "seqimages-decoder.exe" : "seqimages-decoder");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    @Test
    void repeatedUseRejectsSameSizeTampering() throws Exception {
        isolatedRoot(() -> {
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
    void deletedHelperIsRestoredFromBundledBytes() throws Exception {
        isolatedRoot(() -> {
            Path file = helper();
            byte[] expected = Files.readAllBytes(file);
            Files.delete(file);
            assertEquals(file, helper());
            assertArrayEquals(expected, Files.readAllBytes(file));
        });
    }
}
