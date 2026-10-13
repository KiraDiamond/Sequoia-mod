package com.seqwawa.seq.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.ui.UpdatePromptScreen;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class UpdateManager implements NotificationAccessor {
    private static final int MAX_REDIRECTS = 5;
    private static final String MOD_ID = "seq";
    private static final String REPO_OWNER = "SequoiaWynncraft";
    private static final String REPO_NAME = "Sequoia-mod";
    private static final URI DEFAULT_RELEASES_LATEST_API =
            URI.create("https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest");
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");

    private static UpdateManager instance;

    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Gson gson;
    private final URI latestReleaseApi;

    private volatile boolean startupChecked;
    private volatile boolean checking;
    private volatile boolean applying;
    private volatile String sessionIgnoredTag;
    private volatile String statusLine = "Idle";
    private volatile ReleaseCandidate pendingRelease;
    private volatile PendingInstall pendingInstall;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    public static UpdateManager getInstance() {
        if (instance == null) {
            instance = new UpdateManager();
        }
        return instance;
    }

    UpdateManager() {
        this(createExecutor());
    }

    private UpdateManager(ExecutorService executor) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .executor(executor)
                        .build(),
                executor,
                new Gson(),
                DEFAULT_RELEASES_LATEST_API);
    }

    UpdateManager(HttpClient httpClient, ExecutorService executor, Gson gson, URI latestReleaseApi) {
        this.httpClient = httpClient;
        this.executor = executor;
        this.gson = gson;
        this.latestReleaseApi = latestReleaseApi;
    }

    private static ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "seq-updater");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void checkForUpdatesOnStartup() {
        if (startupChecked) {
            return;
        }
        startupChecked = true;
        checkForUpdates(false);
    }

    public void checkForUpdatesManually() {
        checkForUpdates(true);
    }

    public String getStatusLine() {
        return statusLine;
    }

    public void ignoreForSession(String tag) {
        this.sessionIgnoredTag = tag;
        this.statusLine = "Ignored " + tag + " for this session.";
    }

    public void applyPendingUpdate(boolean exitAfterInstall) {
        ReleaseCandidate target = pendingRelease;
        if (target == null) {
            sendNotification("No pending update available.");
            statusLine = "No pending update available.";
            return;
        }
        if (applying) {
            sendNotification("Update install already in progress.");
            return;
        }

        applying = true;
        statusLine = "Installing " + target.tagName() + "...";
        CompletableFuture.runAsync(() -> applyRelease(target, exitAfterInstall), executor)
                .whenComplete((ignored, throwable) -> {
                    applying = false;
                    if (throwable != null) {
                        statusLine = "Install failed: " + rootCauseMessage(throwable);
                        sendNotification("Update failed: " + rootCauseMessage(throwable));
                        SeqClient.LOGGER.error("Failed to apply update", throwable);
                    }
                });
    }

    public void openReleasePage() {
        ReleaseCandidate target = pendingRelease;
        if (target == null) {
            return;
        }
        try {
            openBrowser(target.releasePageUrl());
        } catch (Exception e) {
            sendClickableNotification("Open latest release", target.releasePageUrl());
        }
    }

    private void checkForUpdates(boolean manualTrigger) {
        if (checking) {
            if (manualTrigger) {
                sendNotification("Update check already running.");
            }
            return;
        }

        checking = true;
        statusLine = "Checking for updates...";
        fetchLatestRelease().whenComplete((candidate, throwable) -> {
            checking = false;
            if (throwable != null) {
                statusLine = "Update check failed: " + rootCauseMessage(throwable);
                if (manualTrigger) {
                    sendNotification("Update check failed: " + rootCauseMessage(throwable));
                }
                SeqClient.LOGGER.warn("Update check failed", throwable);
                return;
            }
            if (candidate == null) {
                statusLine = "No valid release found.";
                if (manualTrigger) {
                    sendNotification("No valid release found.");
                }
                return;
            }

            String installedVersion = resolveInstalledVersion();
            if (!isNewer(candidate.tagName(), installedVersion)) {
                pendingRelease = null;
                statusLine = "Up to date (" + installedVersion + ").";
                if (manualTrigger) {
                    sendNotification("You are up to date (" + installedVersion + ").");
                }
                return;
            }

            if (Objects.equals(sessionIgnoredTag, candidate.tagName()) && !manualTrigger) {
                statusLine = "Update " + candidate.tagName() + " ignored for this session.";
                return;
            }

            pendingRelease = candidate;
            statusLine = "Update available: " + installedVersion + " -> " + candidate.tagName();
            showUpdatePrompt(installedVersion, candidate);
        });
    }

    CompletableFuture<ReleaseCandidate> fetchLatestRelease() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(latestReleaseApi)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Sequoia-Updater")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new IllegalStateException("GitHub API returned " + response.statusCode());
                    }
                    JsonObject root = gson.fromJson(response.body(), JsonObject.class);

                    String tagName = getString(root, "tag_name");
                    if (tagName == null || !SEMVER_PATTERN.matcher(tagName).matches()) {
                        throw new IllegalStateException("Release has invalid tag name: " + tagName);
                    }

                    String releasePage = getString(root, "html_url");
                    JsonArray assets = root.has("assets") && root.get("assets").isJsonArray()
                            ? root.getAsJsonArray("assets")
                            : new JsonArray();

                    ReleaseAsset jarAsset = null;
                    for (JsonElement assetElement : assets) {
                        if (!assetElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject assetObj = assetElement.getAsJsonObject();
                        String name = getString(assetObj, "name");
                        if (name == null) {
                            continue;
                        }
                        if (!name.endsWith(".jar") || name.endsWith("-sources.jar")) {
                            continue;
                        }
                        if (!name.startsWith("sequoia-")) {
                            continue;
                        }
                        String url = getString(assetObj, "browser_download_url");
                        if (url != null) {
                            jarAsset = new ReleaseAsset(name, url, parseSha256Digest(getString(assetObj, "digest")));
                            break;
                        }
                    }

                    if (jarAsset == null) {
                        throw new IllegalStateException("Release missing primary mod jar asset.");
                    }

                    ReleaseAsset checksumAsset = null;
                    String expectedChecksumName = jarAsset.name() + ".sha256";
                    for (JsonElement assetElement : assets) {
                        if (!assetElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject assetObj = assetElement.getAsJsonObject();
                        String name = getString(assetObj, "name");
                        if (!expectedChecksumName.equals(name)) {
                            continue;
                        }
                        String url = getString(assetObj, "browser_download_url");
                        if (url != null) {
                            checksumAsset = new ReleaseAsset(name, url);
                            break;
                        }
                    }

                    if (checksumAsset == null) {
                        throw new IllegalStateException(
                                "Release missing required checksum asset " + expectedChecksumName + ".");
                    }

                    return new ReleaseCandidate(tagName, releasePage, jarAsset, checksumAsset);
                });
    }

    void applyRelease(ReleaseCandidate release, boolean exitAfterInstall) {
        try {
            Path gameDir = resolveGameDir();
            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);

            Path updatesDir = gameDir.resolve("updates");
            Files.createDirectories(updatesDir);

            Path tempJar = updatesDir.resolve(release.jarAsset().name() + ".download");
            Path pendingJar = updatesDir.resolve(release.jarAsset().name() + ".pending");
            Path finalJar = modsDir.resolve(release.jarAsset().name());

            downloadToFile(release.jarAsset().downloadUrl(), tempJar);
            String expectedChecksum = resolveExpectedChecksum(release);
            String actualChecksum = sha256(tempJar);
            if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                Files.deleteIfExists(tempJar);
                throw new IllegalStateException("Checksum mismatch for downloaded update.");
            }

            if (isWindows()) {
                Path currentJar = resolveInstalledModJarPath();
                Path helperSourceJar = resolveWindowsHelperSourceJarPath(currentJar);
                moveAtomically(tempJar, pendingJar);
                pendingInstall =
                        new PendingInstall(pendingJar, finalJar, modsDir, release.tagName(), currentJar, helperSourceJar);
                registerShutdownHookIfNeeded();
                pendingRelease = null;
                statusLine = "Downloaded " + release.tagName() + ". It will install on exit.";
                sendNotification("Downloaded " + release.tagName() + ". It will install when Minecraft closes.");

                if (exitAfterInstall) {
                    requestMinecraftStop();
                }
                return;
            }

            deleteExistingSequoiaJars(modsDir);
            moveAtomically(tempJar, finalJar);

            pendingRelease = null;
            statusLine = "Installed " + release.tagName() + ". Restart required.";
            sendNotification("Installed " + release.tagName() + ". Restart Minecraft to load it.");

            if (exitAfterInstall) {
                requestMinecraftStop();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Path resolveGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    void showUpdatePrompt(String installedVersion, ReleaseCandidate candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(
                () -> minecraft.setScreen(new UpdatePromptScreen(minecraft.screen, installedVersion, candidate)));
    }

    void requestMinecraftStop() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    void sendNotification(String message) {
        notify(message);
    }

    void sendClickableNotification(String text, String url) {
        notifyClickable(text, url);
    }

    void openBrowser(String url) throws Exception {
        Desktop.getDesktop().browse(URI.create(url));
    }

    private void registerShutdownHookIfNeeded() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }
        addShutdownHook(new Thread(this::applyPendingInstallOnShutdown, "seq-update-shutdown"));
    }

    void addShutdownHook(Thread thread) {
        Runtime.getRuntime().addShutdownHook(thread);
    }

    void applyPendingInstallOnShutdown() {
        PendingInstall install = pendingInstall;
        if (install == null || !Files.exists(install.pendingJar())) {
            return;
        }

        try {
            if (isWindows()) {
                launchWindowsHelper(install);
                return;
            }

            deleteExistingSequoiaJars(install.modsDir());
            moveAtomically(install.pendingJar(), install.finalJar());
            SeqClient.LOGGER.info("Applied pending update {} on shutdown", install.tagName());
        } catch (Exception e) {
            SeqClient.LOGGER.warn("Failed to apply pending shutdown update", e);
        }
    }

    private void launchWindowsHelper(PendingInstall install) {
        try {
            Path updatesDir = install.pendingJar().getParent();
            if (updatesDir == null) {
                throw new IllegalStateException("Missing updates directory for pending install.");
            }

            Path sourceJar = install.helperSourceJar();
            Path helperJar = updatesDir.resolve("seq-update-helper.jar");
            Files.copy(sourceJar, helperJar, StandardCopyOption.REPLACE_EXISTING);

            launchUpdateHelperProcess(
                    resolveJavaExecutable(),
                    helperJar,
                    install.modsDir(),
                    install.currentJar(),
                    install.pendingJar(),
                    install.finalJar());

            SeqClient.LOGGER.info("Launched Windows update helper for {}", install.tagName());
        } catch (Exception e) {
            SeqClient.LOGGER.warn("Failed to launch Windows update helper", e);
        }
    }

    void launchUpdateHelperProcess(
            String javaExe, Path helperJar, Path modsDir, Path currentJar, Path pendingJar, Path finalJar)
            throws IOException {
        new ProcessBuilder(
                        javaExe,
                        "-cp",
                        helperJar.toString(),
                        UpdateApplier.class.getName(),
                        modsDir.toString(),
                        currentJar.toString(),
                        pendingJar.toString(),
                        finalJar.toString(),
                        helperJar.toString())
                .start();
    }

    Path resolveWindowsHelperSourceJarPath(Path installedJar) {
        try {
            return resolveCurrentModJarPath();
        } catch (Exception e) {
            SeqClient.LOGGER.warn(
                    "Falling back to installed mod jar for Windows helper source resolution", e);
            return installedJar;
        }
    }

    Path resolveInstalledModJarPath() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(this::resolveInstalledModJarPath)
                    .orElseGet(this::resolveCurrentModJarPath);
        } catch (Exception e) {
            SeqClient.LOGGER.warn("Falling back to runtime updater jar path for installed mod resolution", e);
            return resolveCurrentModJarPath();
        }
    }

    private Path resolveInstalledModJarPath(ModContainer container) {
        ModOrigin origin = container.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) {
            throw new IllegalStateException("Fabric Loader mod origin was " + origin.getKind() + ", expected PATH.");
        }

        for (Path path : origin.getPaths()) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                return path.toAbsolutePath().normalize();
            }
        }

        throw new IllegalStateException("Fabric Loader mod origin did not expose an installed jar path.");
    }

    Path resolveCurrentModJarPath() {
        try {
            URI location = UpdateManager.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            Path path = Path.of(location);
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        "Updater helper requires a packaged mod jar (not a development classes directory).");
            }
            return path;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve updater jar location.", e);
        }
    }

    String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaExe = Path.of(javaHome, "bin", "java.exe");
            if (Files.exists(javaExe)) {
                return javaExe.toString();
            }
            Path javaBin = Path.of(javaHome, "bin", "java");
            if (Files.exists(javaBin)) {
                return javaBin.toString();
            }
        }
        return "java";
    }

    boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    private void downloadToFile(String url, Path targetFile) throws IOException, InterruptedException {
        HttpRequest request = buildDownloadRequest(URI.create(url), Duration.ofSeconds(60), null);
        HttpResponse<InputStream> response =
                sendFollowingRedirects(request, HttpResponse.BodyHandlers.ofInputStream(), MAX_REDIRECTS);
        try (InputStream input = response.body()) {
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String downloadToString(String url) throws IOException, InterruptedException {
        HttpRequest request =
                buildDownloadRequest(URI.create(url), Duration.ofSeconds(30), StandardCharsets.UTF_8.name());
        HttpResponse<String> response = sendFollowingRedirects(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_REDIRECTS);
        return response.body();
    }

    private HttpRequest buildDownloadRequest(URI uri, Duration timeout, String acceptHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Sequoia-Updater")
                .timeout(timeout)
                .GET();
        if (acceptHeader != null) {
            builder.header("Accept", acceptHeader);
        }
        return builder.build();
    }

    private <T> HttpResponse<T> sendFollowingRedirects(
            HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, int redirectsRemaining)
            throws IOException, InterruptedException {
        HttpResponse<T> response = httpClient.send(request, bodyHandler);
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            if (redirectsRemaining == 0) {
                throw new IllegalStateException("Download failed: too many redirects.");
            }
            URI redirectUri = resolveRedirectUri(request.uri(), response.headers());
            HttpRequest redirected = buildDownloadRequest(
                    redirectUri,
                    request.timeout().orElse(Duration.ofSeconds(30)),
                    request.headers().firstValue("Accept").orElse(null));
            return sendFollowingRedirects(redirected, bodyHandler, redirectsRemaining - 1);
        }
        if (status >= 400) {
            throw new IllegalStateException("Download failed with status " + status);
        }
        return response;
    }

    private URI resolveRedirectUri(URI originalUri, HttpHeaders headers) {
        String location = headers.firstValue("Location")
                .orElseThrow(() -> new IllegalStateException("Download redirect missing Location header."));
        return originalUri.resolve(location);
    }

    private String resolveExpectedChecksum(ReleaseCandidate release) throws IOException, InterruptedException {
        String embeddedChecksum = release.jarAsset().sha256();
        if (embeddedChecksum != null) {
            return embeddedChecksum;
        }

        String checksumFile = downloadToString(release.checksumAsset().downloadUrl());
        return parseChecksum(checksumFile);
    }

    private void deleteExistingSequoiaJars(Path modsDir) throws IOException {
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(path -> path.getFileName().toString().startsWith("sequoia-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    String resolveInstalledVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    static boolean isNewer(String latest, String installed) {
        Matcher latestMatcher = SEMVER_PATTERN.matcher(latest);
        if (!latestMatcher.matches()) {
            return false;
        }

        Matcher installedMatcher = SEMVER_PATTERN.matcher(installed);
        if (!installedMatcher.matches()) {
            return !latest.equals(installed);
        }

        for (int i = 1; i <= 3; i++) {
            int latestPart = Integer.parseInt(latestMatcher.group(i));
            int installedPart = Integer.parseInt(installedMatcher.group(i));
            if (latestPart > installedPart) {
                return true;
            }
            if (latestPart < installedPart) {
                return false;
            }
        }
        return false;
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String parseChecksum(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("Checksum file was empty.");
        }
        String[] split = trimmed.split("\\s+");
        if (split.length == 0 || split[0].length() != 64) {
            throw new IllegalStateException("Checksum file had invalid format.");
        }
        return split[0];
    }

    static String parseSha256Digest(String raw) {
        if (raw == null) {
            return null;
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String normalized = trimmed.regionMatches(true, 0, "sha256:", 0, 7) ? trimmed.substring(7) : trimmed;

        if (normalized.length() != 64 || !normalized.chars().allMatch(UpdateManager::isHexCharacter)) {
            return null;
        }

        return normalized.toLowerCase();
    }

    private static boolean isHexCharacter(int value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F');
    }

    static String getString(JsonObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    static String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    public record ReleaseCandidate(
            String tagName, String releasePageUrl, ReleaseAsset jarAsset, ReleaseAsset checksumAsset) {}

    public record ReleaseAsset(String name, String downloadUrl, String sha256) {
        public ReleaseAsset(String name, String downloadUrl) {
            this(name, downloadUrl, null);
        }
    }

    record PendingInstall(
            Path pendingJar, Path finalJar, Path modsDir, String tagName, Path currentJar, Path helperSourceJar) {}
}
