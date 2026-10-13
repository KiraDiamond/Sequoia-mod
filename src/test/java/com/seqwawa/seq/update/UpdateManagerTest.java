package com.seqwawa.seq.update;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerTest {
    private static final URI UNUSED_RELEASES_API = URI.create("https://example.invalid/releases/latest");

    @Test
    void isNewerComparesStrictVersionTags() {
        assertTrue(UpdateManager.isNewer("v0.1.1", "v0.1.0"));
        assertTrue(UpdateManager.isNewer("v1.0.0", "not-a-tag"));
        assertFalse(UpdateManager.isNewer("v0.1.0", "v0.1.0"));
        assertFalse(UpdateManager.isNewer("v0.1.0", "v0.1.1"));
        assertFalse(UpdateManager.isNewer("main", "v0.1.0"));
    }

    @Test
    void parseChecksumAcceptsSha256FileFormat() {
        String checksum = checksumValue();

        assertEquals(checksum, UpdateManager.parseChecksum(checksum + "  sequoia.jar\n"));
    }

    @Test
    void parseChecksumRejectsEmptyFile() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> UpdateManager.parseChecksum(" \n "));

        assertEquals("Checksum file was empty.", error.getMessage());
    }

    @Test
    void parseChecksumRejectsInvalidFormat() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> UpdateManager.parseChecksum("not-a-checksum  sequoia.jar"));

        assertEquals("Checksum file had invalid format.", error.getMessage());
    }

    @Test
    void parseSha256DigestAcceptsGitHubDigestFormat() {
        String checksum = checksumValue();

        assertEquals(checksum, UpdateManager.parseSha256Digest("sha256:" + checksum.toUpperCase()));
    }

    @Test
    void parseSha256DigestRejectsInvalidFormat() {
        assertNull(UpdateManager.parseSha256Digest("sha256:not-hex"));
    }

    @Test
    void fetchLatestReleaseParsesJarAndChecksumAssets(@TempDir Path gameDir) throws Exception {
        String checksum = checksumValue();

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondJson("/releases/latest", """
                    {
                      "tag_name": "v0.1.1",
                      "html_url": "https://example.invalid/releases/v0.1.1",
                      "assets": [
                        {
                          "name": "sequoia-0.1.1.jar",
                          "browser_download_url": "%s",
                          "digest": "sha256:%s"
                        },
                        {
                          "name": "sequoia-0.1.1.jar.sha256",
                          "browser_download_url": "%s"
                        }
                      ]
                    }
                    """.formatted(
                    server.uri("/downloads/sequoia-0.1.1.jar"), checksum, server.uri("/downloads/sequoia-0.1.1.jar.sha256")));

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                UpdateManager.ReleaseCandidate release = manager.fetchLatestRelease().join();

                assertNotNull(release);
                assertEquals("v0.1.1", release.tagName());
                assertEquals("sequoia-0.1.1.jar", release.jarAsset().name());
                assertEquals(server.uri("/downloads/sequoia-0.1.1.jar").toString(), release.jarAsset().downloadUrl());
                assertEquals(checksum, release.jarAsset().sha256());
                assertEquals("sequoia-0.1.1.jar.sha256", release.checksumAsset().name());
            }
        }
    }

    @Test
    void fetchLatestReleaseRejectsInvalidTag(@TempDir Path gameDir) throws Exception {
        assertFetchFailure(gameDir, """
                {
                  "tag_name": "latest",
                  "html_url": "https://example.invalid/releases/latest",
                  "assets": []
                }
                """, "Release has invalid tag name: latest");
    }

    @Test
    void fetchLatestReleaseRejectsMissingPrimaryJarAsset(@TempDir Path gameDir) throws Exception {
        assertFetchFailure(gameDir, """
                {
                  "tag_name": "v0.1.1",
                  "html_url": "https://example.invalid/releases/v0.1.1",
                  "assets": [
                    {
                      "name": "notes.txt",
                      "browser_download_url": "https://example.invalid/notes.txt"
                    }
                  ]
                }
                """, "Release missing primary mod jar asset.");
    }

    @Test
    void fetchLatestReleaseRejectsMissingChecksumAsset(@TempDir Path gameDir) throws Exception {
        assertFetchFailure(gameDir, """
                {
                  "tag_name": "v0.1.1",
                  "html_url": "https://example.invalid/releases/v0.1.1",
                  "assets": [
                    {
                      "name": "sequoia-0.1.1.jar",
                      "browser_download_url": "https://example.invalid/sequoia-0.1.1.jar"
                    }
                  ]
                }
                """, "Release missing required checksum asset sequoia-0.1.1.jar.sha256.");
    }

    @Test
    void fetchLatestReleaseRejectsGitHubApiErrors(@TempDir Path gameDir) throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.respondStatus("/releases/latest", 503, "unavailable", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                CompletionException error =
                        assertThrows(CompletionException.class, () -> manager.fetchLatestRelease().join());

                assertEquals("GitHub API returned 503", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void checkForUpdatesOnStartupRunsOnlyOnce(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        manager.enqueueFetchResult(CompletableFuture.completedFuture(null));
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release("v0.2.0")));

        manager.checkForUpdatesOnStartup();
        manager.checkForUpdatesOnStartup();

        assertEquals(1, manager.fetchCalls.get());
        assertEquals("No valid release found.", manager.getStatusLine());
    }

    @Test
    void checkForUpdatesManualUpToDateNotifiesAndClearsPendingRelease(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        UpdateManager.ReleaseCandidate release = release("v0.1.1");

        manager.installedVersion = "0.1.0";
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesManually();
        assertEquals(1, manager.prompts.size());

        manager.installedVersion = "v0.1.1";
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesManually();

        assertEquals("Up to date (v0.1.1).", manager.getStatusLine());
        assertTrue(manager.notifications.contains("You are up to date (v0.1.1)."));

        manager.openReleasePage();
        assertEquals(0, manager.browserOpenCount.get());
    }

    @Test
    void checkForUpdatesIgnoresSessionTagOnStartupButShowsPromptOnManualCheck(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        UpdateManager.ReleaseCandidate release = release("v0.1.1");

        manager.installedVersion = "v0.1.0";
        manager.ignoreForSession("v0.1.1");
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesOnStartup();

        assertEquals("Update v0.1.1 ignored for this session.", manager.getStatusLine());
        assertTrue(manager.prompts.isEmpty());

        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesManually();

        assertEquals(1, manager.prompts.size());
        assertEquals("Update available: v0.1.0 -> v0.1.1", manager.getStatusLine());
    }

    @Test
    void checkForUpdatesManualFailureNotifiesButStartupFailureDoesNot(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);

        manager.enqueueFetchResult(CompletableFuture.failedFuture(new IllegalStateException("startup failure")));
        manager.checkForUpdatesOnStartup();
        assertTrue(manager.notifications.isEmpty());
        assertEquals("Update check failed: startup failure", manager.getStatusLine());

        manager.enqueueFetchResult(CompletableFuture.failedFuture(new IllegalStateException("manual failure")));
        manager.checkForUpdatesManually();

        assertEquals("Update check failed: manual failure", manager.getStatusLine());
        assertEquals(List.of("Update check failed: manual failure"), manager.notifications);
    }

    @Test
    void checkForUpdatesManualNullCandidateNotifies(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        manager.enqueueFetchResult(CompletableFuture.completedFuture(null));

        manager.checkForUpdatesManually();

        assertEquals("No valid release found.", manager.getStatusLine());
        assertEquals(List.of("No valid release found."), manager.notifications);
    }

    @Test
    void checkForUpdatesAlreadyRunningOnlyNotifiesManualCallers(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        CompletableFuture<UpdateManager.ReleaseCandidate> pending = new CompletableFuture<>();
        manager.enqueueFetchResult(pending);

        manager.checkForUpdatesOnStartup();
        manager.checkForUpdatesManually();

        assertEquals(List.of("Update check already running."), manager.notifications);
        assertEquals("Checking for updates...", manager.getStatusLine());

        pending.complete(null);
        assertEquals("No valid release found.", manager.getStatusLine());
    }

    @Test
    void openReleasePageFallsBackToClickableNotificationWhenBrowserFails(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        UpdateManager.ReleaseCandidate release = release("v0.1.1");
        manager.installedVersion = "v0.1.0";
        manager.browserShouldFail = true;
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesManually();

        manager.openReleasePage();

        assertEquals(1, manager.browserOpenCount.get());
        assertEquals(List.of("Open latest release -> https://example.invalid/releases/v0.1.1"), manager.clickableNotifications);
    }

    @Test
    void applyPendingUpdateWithoutPendingReleaseNotifies(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);

        manager.applyPendingUpdate(false);

        assertEquals("No pending update available.", manager.getStatusLine());
        assertEquals(List.of("No pending update available."), manager.notifications);
    }

    @Test
    void applyPendingUpdateReportsFailureAndDoesNotStopMinecraft(@TempDir Path gameDir) {
        TestableUpdateManager manager = newDirectManager(gameDir);
        manager.skipRealApply = true;
        manager.applyFailure = new RuntimeException(new IllegalStateException("install boom"));
        seedPendingRelease(manager, release("v0.1.1"));

        manager.applyPendingUpdate(true);

        assertEquals("Install failed: install boom", manager.getStatusLine());
        assertTrue(manager.notifications.contains("Update failed: install boom"));
        assertEquals(0, manager.stopRequests.get());
    }

    @Test
    void applyPendingUpdateRejectsConcurrentInstalls(@TempDir Path gameDir) throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TestableUpdateManager manager = newManager(executor, UNUSED_RELEASES_API, gameDir);
            manager.skipRealApply = true;
            manager.applyStarted = new CountDownLatch(1);
            manager.allowApplyToFinish = new CountDownLatch(1);
            seedPendingRelease(manager, release("v0.1.1"));

            manager.applyPendingUpdate(false);
            assertTrue(manager.applyStarted.await(2, TimeUnit.SECONDS));

            manager.applyPendingUpdate(false);

            assertTrue(manager.notifications.contains("Update install already in progress."));

            manager.allowApplyToFinish.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void applyReleaseInstallsJarRemovesPreviousVersionAndPreservesOtherMods(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("sequoia-0.1.0.jar"), "old");
            Files.writeString(modsDir.resolve("othermod.jar"), "keep");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                manager.applyRelease(
                        release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                        false);

                Path installedJar = modsDir.resolve("sequoia-0.1.1.jar");
                assertTrue(Files.exists(installedJar));
                assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));
                assertFalse(Files.exists(modsDir.resolve("sequoia-0.1.0.jar")));
                assertTrue(Files.exists(modsDir.resolve("othermod.jar")));
                assertEquals("Installed v0.1.1. Restart required.", manager.getStatusLine());
                assertEquals(List.of("Installed v0.1.1. Restart Minecraft to load it."), manager.notifications);
            }
        }
    }

    @Test
    void applyReleaseDeletesTemporaryJarWhenChecksumDoesNotMatch(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText(
                    "/downloads/sequoia-0.1.1.jar.sha256",
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  sequoia-0.1.1.jar\n",
                    "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Checksum mismatch for downloaded update.", UpdateManager.rootCauseMessage(error));
                assertFalse(Files.exists(gameDir.resolve("updates").resolve("sequoia-0.1.1.jar.download")));
            }
        }
    }

    @Test
    void applyReleaseFollowsRedirectsForJarAndChecksumDownloads(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.redirect("/downloads/sequoia-0.1.1.jar", "/storage/sequoia-0.1.1.jar");
            server.respondBytes("/storage/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.redirect("/downloads/sequoia-0.1.1.jar.sha256", "/storage/sequoia-0.1.1.jar.sha256");
            server.respondText("/storage/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                manager.applyRelease(
                        release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                        false);

                Path installedJar = modsDir.resolve("sequoia-0.1.1.jar");
                assertTrue(Files.exists(installedJar));
                assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));
            }
        }
    }

    @Test
    void applyReleaseUsesEmbeddedDigestWhenChecksumFileIsEmpty(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", "", "text/plain");

            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                manager.applyRelease(
                        release(
                                "v0.1.1",
                                server.uri("/downloads/sequoia-0.1.1.jar"),
                                server.uri("/downloads/sequoia-0.1.1.jar.sha256"),
                                checksum),
                        false);

                Path installedJar = modsDir.resolve("sequoia-0.1.1.jar");
                assertTrue(Files.exists(installedJar));
                assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenChecksumFileIsEmptyWithoutEmbeddedDigest(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", "", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Checksum file was empty.", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenChecksumFileIsMalformed(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", "invalid", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Checksum file had invalid format.", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenJarDownloadRedirectsTooManyTimes(@TempDir Path gameDir) throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.redirect("/downloads/sequoia-0.1.1.jar", "/downloads/sequoia-0.1.1.jar");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksumValue() + "  sequoia-0.1.1.jar\n", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Download failed: too many redirects.", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenRedirectResponseIsMissingLocationHeader(@TempDir Path gameDir) throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.redirectWithoutLocation("/downloads/sequoia-0.1.1.jar");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksumValue() + "  sequoia-0.1.1.jar\n", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Download redirect missing Location header.", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenJarDownloadReturnsHttpError(@TempDir Path gameDir) throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.respondStatus("/downloads/sequoia-0.1.1.jar", 404, "missing", "text/plain");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksumValue() + "  sequoia-0.1.1.jar\n", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Download failed with status 404", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyReleaseFailsWhenChecksumDownloadReturnsHttpError(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondStatus("/downloads/sequoia-0.1.1.jar.sha256", 500, "broken", "text/plain");

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                RuntimeException error = assertThrows(
                        RuntimeException.class,
                        () -> manager.applyRelease(
                                release(
                                        "v0.1.1",
                                        server.uri("/downloads/sequoia-0.1.1.jar"),
                                        server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                                false));

                assertEquals("Download failed with status 500", UpdateManager.rootCauseMessage(error));
            }
        }
    }

    @Test
    void applyPendingUpdateOnWindowsStagesPendingInstallRegistersHookAndStopsWhenRequested(@TempDir Path gameDir)
            throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            manager.currentModJarPath = Files.writeString(gameDir.resolve("seq-current.jar"), "packaged");
            seedPendingRelease(
                    manager,
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")));

            manager.applyPendingUpdate(true);

            assertTrue(Files.exists(gameDir.resolve("updates").resolve("sequoia-0.1.1.jar.pending")));
            assertFalse(Files.exists(gameDir.resolve("mods").resolve("sequoia-0.1.1.jar")));
            assertEquals("Downloaded v0.1.1. It will install on exit.", manager.getStatusLine());
            assertTrue(manager.notifications.contains("Downloaded v0.1.1. It will install when Minecraft closes."));
            assertEquals(1, manager.stopRequests.get());
            assertEquals(1, manager.shutdownHooks.size());

            manager.applyPendingUpdate(false);
            assertTrue(manager.notifications.contains("No pending update available."));
        }
    }

    @Test
    void applyReleaseOnWindowsRegistersShutdownHookOnlyOnce(@TempDir Path gameDir) throws Exception {
        byte[] jarOne = "jar-one".getBytes(StandardCharsets.UTF_8);
        byte[] jarTwo = "jar-two".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarOne, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", sha256(jarOne) + "  sequoia-0.1.1.jar\n", "text/plain");
            server.respondBytes("/downloads/sequoia-0.1.2.jar", jarTwo, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.2.jar.sha256", sha256(jarTwo) + "  sequoia-0.1.2.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            manager.currentModJarPath = Files.writeString(gameDir.resolve("seq-current.jar"), "packaged");

            manager.applyRelease(
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                    false);
            manager.applyRelease(
                    release("v0.1.2", server.uri("/downloads/sequoia-0.1.2.jar"), server.uri("/downloads/sequoia-0.1.2.jar.sha256")),
                    false);

            assertEquals(1, manager.shutdownHooks.size());
        }
    }

    @Test
    void applyPendingInstallOnShutdownMovesPendingJarAndDeletesStaleSequoiaJars(@TempDir Path gameDir)
            throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            manager.currentModJarPath = Files.writeString(gameDir.resolve("seq-current.jar"), "packaged");
            manager.applyRelease(
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                    false);

            Path modsDir = gameDir.resolve("mods");
            Files.writeString(modsDir.resolve("sequoia-0.0.9.jar"), "stale");
            Files.writeString(modsDir.resolve("othermod.jar"), "keep");

            manager.windows = false;
            manager.applyPendingInstallOnShutdown();

            Path finalJar = modsDir.resolve("sequoia-0.1.1.jar");
            assertTrue(Files.exists(finalJar));
            assertArrayEquals(jarBytes, Files.readAllBytes(finalJar));
            assertFalse(Files.exists(gameDir.resolve("updates").resolve("sequoia-0.1.1.jar.pending")));
            assertFalse(Files.exists(modsDir.resolve("sequoia-0.0.9.jar")));
            assertTrue(Files.exists(modsDir.resolve("othermod.jar")));
        }
    }

    @Test
    void applyPendingInstallOnShutdownLaunchesWindowsHelper(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            manager.currentModJarPath = Files.writeString(gameDir.resolve("seq-runtime.jar"), "packaged helper source");
            Files.createDirectories(gameDir.resolve("mods"));
            manager.installedModJarPath = Files.writeString(
                    gameDir.resolve("mods").resolve("sequoia-0.1.0.jar"), "installed mod jar");
            manager.javaExecutable = "java-test";
            manager.applyRelease(
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                    false);

            manager.applyPendingInstallOnShutdown();

            assertEquals(1, manager.helperLaunches.size());
            HelperLaunch launch = manager.helperLaunches.getFirst();
            assertEquals("java-test", launch.javaExecutable());
            assertEquals(gameDir.resolve("mods"), launch.modsDir());
            assertEquals(manager.installedModJarPath, launch.currentJar());
            assertEquals(gameDir.resolve("updates").resolve("sequoia-0.1.1.jar.pending"), launch.pendingJar());
            assertEquals(gameDir.resolve("mods").resolve("sequoia-0.1.1.jar"), launch.finalJar());
            assertEquals(gameDir.resolve("updates").resolve("seq-update-helper.jar"), launch.helperJar());
            assertArrayEquals(
                    Files.readAllBytes(manager.currentModJarPath),
                    Files.readAllBytes(gameDir.resolve("updates").resolve("seq-update-helper.jar")));
        }
    }

    @Test
    void applyPendingInstallOnShutdownFallsBackToInstalledJarWhenRuntimeJarResolutionFails(@TempDir Path gameDir)
            throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            Files.createDirectories(gameDir.resolve("mods"));
            manager.installedModJarPath = Files.writeString(
                    gameDir.resolve("mods").resolve("sequoia-0.1.0.jar"), "installed mod jar");
            manager.currentModJarPathFailure = new IllegalStateException("runtime jar unavailable");
            manager.javaExecutable = "java-test";
            manager.applyRelease(
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                    false);

            manager.applyPendingInstallOnShutdown();

            assertEquals(1, manager.helperLaunches.size());
            HelperLaunch launch = manager.helperLaunches.getFirst();
            assertEquals(manager.installedModJarPath, launch.currentJar());
            assertArrayEquals(
                    Files.readAllBytes(manager.installedModJarPath),
                    Files.readAllBytes(gameDir.resolve("updates").resolve("seq-update-helper.jar")));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void applyPendingInstallOnShutdownRunsRealWindowsHelperProcess(@TempDir Path gameDir) throws Exception {
        byte[] jarBytes = "fake-jar-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);

        try (TestHttpServer server = new TestHttpServer()) {
            server.respondBytes("/downloads/sequoia-0.1.1.jar", jarBytes, "application/java-archive");
            server.respondText("/downloads/sequoia-0.1.1.jar.sha256", checksum + "  sequoia-0.1.1.jar\n", "text/plain");

            TestableUpdateManager manager = newDirectManager(gameDir);
            manager.windows = true;
            manager.useRealHelperLaunch = true;
            manager.currentModJarPath = gameDir.resolve("seq-current.jar");
            writeUpdateApplierJar(manager.currentModJarPath);

            manager.applyRelease(
                    release("v0.1.1", server.uri("/downloads/sequoia-0.1.1.jar"), server.uri("/downloads/sequoia-0.1.1.jar.sha256")),
                    false);

            manager.applyPendingInstallOnShutdown();

            Path finalJar = gameDir.resolve("mods").resolve("sequoia-0.1.1.jar");
            Path pendingJar = gameDir.resolve("updates").resolve("sequoia-0.1.1.jar.pending");
            Path helperJar = gameDir.resolve("updates").resolve("seq-update-helper.jar");

            awaitCondition(
                    () -> Files.exists(finalJar) && !Files.exists(pendingJar),
                    20,
                    "Timed out waiting for the Windows helper process to install the update.");
            awaitCondition(
                    () -> !Files.exists(helperJar),
                    10,
                    "Timed out waiting for the Windows helper process to clean up its helper jar.");

            assertEquals(1, manager.helperLaunches.size());
            assertArrayEquals(jarBytes, Files.readAllBytes(finalJar));
        }
    }

    private static void assertFetchFailure(Path gameDir, String responseBody, String expectedMessage) throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.respondJson("/releases/latest", responseBody);

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                TestableUpdateManager manager = newHttpManager(executor, server.uri("/releases/latest"), gameDir);

                CompletionException error =
                        assertThrows(CompletionException.class, () -> manager.fetchLatestRelease().join());

                assertEquals(expectedMessage, UpdateManager.rootCauseMessage(error));
            }
        }
    }

    private static void seedPendingRelease(TestableUpdateManager manager, UpdateManager.ReleaseCandidate release) {
        manager.installedVersion = "v0.1.0";
        manager.enqueueFetchResult(CompletableFuture.completedFuture(release));
        manager.checkForUpdatesManually();
        assertEquals(1, manager.prompts.size());
    }

    private static String checksumValue() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }

    private static UpdateManager.ReleaseCandidate release(String tagName) {
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        String jarName = "sequoia-" + version + ".jar";
        return new UpdateManager.ReleaseCandidate(
                tagName,
                "https://example.invalid/releases/" + tagName,
                new UpdateManager.ReleaseAsset(jarName, "https://example.invalid/downloads/" + jarName),
                new UpdateManager.ReleaseAsset(jarName + ".sha256", "https://example.invalid/downloads/" + jarName + ".sha256"));
    }

    private static UpdateManager.ReleaseCandidate release(String tagName, URI jarUrl, URI checksumUrl) {
        return release(tagName, jarUrl, checksumUrl, null);
    }

    private static UpdateManager.ReleaseCandidate release(
            String tagName, URI jarUrl, URI checksumUrl, String embeddedChecksum) {
        String jarName = fileName(jarUrl);
        return new UpdateManager.ReleaseCandidate(
                tagName,
                "https://example.invalid/releases/" + tagName,
                new UpdateManager.ReleaseAsset(jarName, jarUrl.toString(), embeddedChecksum),
                new UpdateManager.ReleaseAsset(jarName + ".sha256", checksumUrl.toString()));
    }

    private static String fileName(URI uri) {
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void writeUpdateApplierJar(Path jarFile) throws IOException {
        Files.createDirectories(Objects.requireNonNull(jarFile.getParent()));
        try (InputStream classBytes = Objects.requireNonNull(
                        UpdateApplier.class.getResourceAsStream("UpdateApplier.class"),
                        "Missing UpdateApplier class bytes");
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarFile))) {
            output.putNextEntry(new JarEntry("com/seqwawa/seq/update/UpdateApplier.class"));
            classBytes.transferTo(output);
            output.closeEntry();
        }
    }

    private static void awaitCondition(BooleanSupplier condition, int timeoutSeconds, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static TestableUpdateManager newHttpManager(ExecutorService executor, URI latestReleaseApi, Path gameDir) {
        HttpClient httpClient = HttpClient.newBuilder().executor(executor).build();
        return new TestableUpdateManager(httpClient, executor, latestReleaseApi, gameDir);
    }

    private static TestableUpdateManager newManager(ExecutorService executor, URI latestReleaseApi, Path gameDir) {
        return new TestableUpdateManager(HttpClient.newHttpClient(), executor, latestReleaseApi, gameDir);
    }

    private static TestableUpdateManager newDirectManager(Path gameDir) {
        DirectExecutorService executor = new DirectExecutorService();
        return new TestableUpdateManager(HttpClient.newHttpClient(), executor, UNUSED_RELEASES_API, gameDir);
    }

    private record PromptCall(String installedVersion, UpdateManager.ReleaseCandidate candidate) {}

    private record HelperLaunch(
            String javaExecutable, Path helperJar, Path modsDir, Path currentJar, Path pendingJar, Path finalJar) {}

    private static final class TestableUpdateManager extends UpdateManager {
        private final Path gameDir;
        private final Queue<CompletableFuture<ReleaseCandidate>> fetchResults = new ConcurrentLinkedQueue<>();
        private final List<String> notifications = new CopyOnWriteArrayList<>();
        private final List<String> clickableNotifications = new CopyOnWriteArrayList<>();
        private final List<PromptCall> prompts = new CopyOnWriteArrayList<>();
        private final List<Thread> shutdownHooks = new CopyOnWriteArrayList<>();
        private final List<HelperLaunch> helperLaunches = new CopyOnWriteArrayList<>();
        private final AtomicInteger fetchCalls = new AtomicInteger();
        private final AtomicInteger stopRequests = new AtomicInteger();
        private final AtomicInteger browserOpenCount = new AtomicInteger();

        private volatile String installedVersion = "0.1.0";
        private volatile boolean windows;
        private volatile boolean browserShouldFail;
        private volatile boolean skipRealApply;
        private volatile RuntimeException applyFailure;
        private volatile CountDownLatch applyStarted;
        private volatile CountDownLatch allowApplyToFinish;
        private volatile Path currentModJarPath;
        private volatile RuntimeException currentModJarPathFailure;
        private volatile Path installedModJarPath;
        private volatile String javaExecutable;
        private volatile boolean useRealHelperLaunch;

        private TestableUpdateManager(
                HttpClient httpClient, ExecutorService executor, URI latestReleaseApi, Path gameDir) {
            super(httpClient, executor, new Gson(), latestReleaseApi);
            this.gameDir = gameDir;
        }

        void enqueueFetchResult(CompletableFuture<ReleaseCandidate> result) {
            fetchResults.add(result);
        }

        @Override
        CompletableFuture<ReleaseCandidate> fetchLatestRelease() {
            fetchCalls.incrementAndGet();
            CompletableFuture<ReleaseCandidate> result = fetchResults.poll();
            return result != null ? result : super.fetchLatestRelease();
        }

        @Override
        Path resolveGameDir() {
            return gameDir;
        }

        @Override
        String resolveInstalledVersion() {
            return installedVersion;
        }

        @Override
        boolean isWindows() {
            return windows;
        }

        @Override
        void showUpdatePrompt(String installedVersion, ReleaseCandidate candidate) {
            prompts.add(new PromptCall(installedVersion, candidate));
        }

        @Override
        void requestMinecraftStop() {
            stopRequests.incrementAndGet();
        }

        @Override
        void sendNotification(String message) {
            notifications.add(message);
        }

        @Override
        void sendClickableNotification(String text, String url) {
            clickableNotifications.add(text + " -> " + url);
        }

        @Override
        void openBrowser(String url) throws Exception {
            browserOpenCount.incrementAndGet();
            if (browserShouldFail) {
                throw new IOException("Browser unavailable");
            }
        }

        @Override
        void addShutdownHook(Thread thread) {
            shutdownHooks.add(thread);
        }

        @Override
        Path resolveCurrentModJarPath() {
            if (currentModJarPathFailure != null) {
                throw currentModJarPathFailure;
            }
            return currentModJarPath != null ? currentModJarPath : super.resolveCurrentModJarPath();
        }

        @Override
        Path resolveInstalledModJarPath() {
            if (installedModJarPath != null) {
                return installedModJarPath;
            }
            if (currentModJarPath != null) {
                return currentModJarPath;
            }
            return super.resolveInstalledModJarPath();
        }

        @Override
        String resolveJavaExecutable() {
            return javaExecutable != null ? javaExecutable : super.resolveJavaExecutable();
        }

        @Override
        void launchUpdateHelperProcess(
                String javaExe, Path helperJar, Path modsDir, Path currentJar, Path pendingJar, Path finalJar)
                throws IOException {
            helperLaunches.add(new HelperLaunch(javaExe, helperJar, modsDir, currentJar, pendingJar, finalJar));
            if (useRealHelperLaunch) {
                super.launchUpdateHelperProcess(javaExe, helperJar, modsDir, currentJar, pendingJar, finalJar);
            }
        }

        @Override
        void applyRelease(ReleaseCandidate release, boolean exitAfterInstall) {
            CountDownLatch started = applyStarted;
            if (started != null) {
                started.countDown();
            }
            CountDownLatch gate = allowApplyToFinish;
            if (gate != null) {
                try {
                    gate.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            if (applyFailure != null) {
                throw applyFailure;
            }
            if (!skipRealApply) {
                super.applyRelease(release, exitAfterInstall);
            }
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.start();
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        void respondJson(String path, String body) {
            respondText(path, body, "application/json");
        }

        void respondText(String path, String body, String contentType) {
            respondBytes(path, body.getBytes(StandardCharsets.UTF_8), 200, contentType);
        }

        void respondBytes(String path, byte[] body, String contentType) {
            respondBytes(path, body, 200, contentType);
        }

        void respondStatus(String path, int status, String body, String contentType) {
            respondBytes(path, body.getBytes(StandardCharsets.UTF_8), status, contentType);
        }

        void redirect(String path, String location) {
            server.createContext(path, exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().add("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                }
            });
        }

        void redirectWithoutLocation(String path) {
            server.createContext(path, exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(302, -1);
                }
            });
        }

        private void respondBytes(String path, byte[] body, int status, String contentType) {
            server.createContext(path, exchange -> writeResponse(exchange, body, status, contentType));
        }

        private void writeResponse(HttpExchange exchange, byte[] body, int status, String contentType)
                throws IOException {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
