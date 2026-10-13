package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class HelperIntegrityTest {
    @FunctionalInterface
    interface Check {
        void run() throws Exception;
    }

    /** Runs {@code check} against a fresh extraction root, so tampering cannot affect other tests. */
    private void isolatedRoot(Check check) throws Exception {
        Field root = IsolatedDecoder.class.getDeclaredField("extractionRoot");
        Field identity = IsolatedDecoder.class.getDeclaredField("extractionRootIdentity");
        root.setAccessible(true);
        identity.setAccessible(true);
        Object previousRoot = root.get(null), previousIdentity = identity.get(null);
        root.set(null, null);
        try {
            check.run();
        } finally {
            root.set(null, previousRoot);
            identity.set(null, previousIdentity);
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no file key; the root is in the user's own temp directory")
    void anotherDirectoryAtTheRootPathIsNotTrusted() throws Exception {
        isolatedRoot(() -> {
            Path original = helper().getParent().getParent();
            Path replaced = original.resolveSibling(original.getFileName() + "-replaced");
            Files.move(original, replaced);
            Files.createDirectory(original);
            try (var leftovers = Files.walk(replaced)) {
                assertNotEquals(original, helper().getParent().getParent());
                Files.delete(original);
                for (Path path : leftovers.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        });
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
