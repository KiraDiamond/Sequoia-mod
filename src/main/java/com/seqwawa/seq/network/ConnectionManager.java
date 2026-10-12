package com.seqwawa.seq.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.seqwawa.seq.model.GuildWarQueueCancellation;
import com.seqwawa.seq.model.GuildWarQueueSubmission;
import com.seqwawa.seq.model.WarStatusUpdate;
import com.seqwawa.seq.model.WarTowerUpdate;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.ConfigManager;
import com.seqwawa.seq.model.ChatItemPreview;
import com.seqwawa.seq.managers.GuildRankEventParser;
import com.seqwawa.seq.managers.GuildStorageTracker;
import com.seqwawa.seq.managers.TreasuryOutManager;
import com.seqwawa.seq.model.BombShareType;
import com.seqwawa.seq.model.GuildWarSubmission;
import com.seqwawa.seq.model.WynnClassType;
import com.seqwawa.seq.network.auth.AuthErrorCode;
import com.seqwawa.seq.network.auth.AuthException;
import com.seqwawa.seq.utils.WynnClassCache;

public class ConnectionManager extends WebSocketClient implements NotificationAccessor {

    private static final Gson GSON = new Gson();
    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_CAP_MS = 60_000;
    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 5;
    // Java UTF-16 units after trim(), matching the backend guild_chat contract.
    private static final int MAX_GUILD_CHAT_MESSAGE_LENGTH = 400;
    private static final long AUTH_BACKOFF_BASE_MS = 2_000;
    private static final long AUTH_BACKOFF_CAP_MS = 60_000;
    private static final long PRIVILEGED_SEND_THROTTLE_MS = 50;
    private static final Pattern MC_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://).+", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> VERSION_REMINDER_INTERVALS = Map.ofEntries(
            Map.entry("bomb_share_request", 5),
            Map.entry("bomb_share_submit", 5),
            Map.entry("treasury_out", 1),
            Map.entry("guild_chat", 20),
            Map.entry("guild_membership_event", 5),
            Map.entry("guild_raid_announcement", 5),
            Map.entry("guild_bank_event", 10),
            Map.entry("guild_storage_snapshot", 10),
            Map.entry("guild_storage_reward", 10),
            Map.entry("guild_war_queue", 5),
            Map.entry("guild_war_queue_cancel", 5),
            Map.entry("guild_war_submission", 5),
            Map.entry("war_status", 5),
            Map.entry("war_tower_update", 5));

    private static ConnectionManager instance;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "seq-reconnect");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService treasuryAuthExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seq-treasury-auth");
        t.setDaemon(true);
        return t;
    });

    @Getter
    private boolean authenticated = false;
    private volatile boolean guildRankTrackingSupported;
    private static final GuildRankObservationQueue guildRankObservations = new GuildRankObservationQueue();

    @Getter
    private boolean authFailed = false;

    @Getter
    private boolean notInGuild = false;

    @Getter
    private Instant connectedSince;

    /** Everyone waiting on a get_connected reply, so the members panel and /seq connected can overlap. */
    private final Queue<Consumer<List<String>>> connectedUsersCallbacks = new ConcurrentLinkedQueue<>();
    private volatile boolean membershipProbePending;
    private volatile boolean memberFeaturesDisabled;
    private volatile long nextAllowedAuthAttemptAtMs;
    private volatile int authAttempt;
    private volatile long nextPrivilegedSendAtMs;
    private volatile boolean connectInProgress;
    private long connectionAttemptGeneration;
    private volatile boolean userInitiatedConnectFlow;
    private volatile boolean treasuryOnlyConnection;
    private final TreasurySessionAuthenticator treasurySessionAuthenticator;
    // WebSocket instances are replaced on reconnect; pending events belong to the account session.
    private static final GuildWarOutboundQueue pendingGuildWarEvents = new GuildWarOutboundQueue();

    // Reconnect state
    private static boolean autoReconnect = true;
    private static int reconnectAttempt = 0;
    private static ScheduledFuture<?> reconnectTask;
    private static boolean autoConnectSuppressedByManualDisconnect;

    // Callbacks for new message types
    private static Consumer<DiscordChatMessage> discordChatHandler;
    private static Consumer<GuildChatMessage> guildChatHandler;
    private static Consumer<PartyFinderUpdateMessage> partyFinderUpdateHandler;
    private static Consumer<RaidProfileUpdateMessage> raidProfileUpdateHandler;
    private static Consumer<PartyFinderInviteMessage> partyFinderInviteHandler;
    private static Consumer<PartyFinderStaleWarningMessage> partyFinderStaleWarningHandler;
    private static Consumer<BombSharePromptMessage> bombSharePromptHandler;
    private static Consumer<BombShareResultMessage> bombShareResultHandler;
    private static Consumer<TreasuryOutRecordedMessage> treasuryOutRecordedHandler;
    private static Predicate<TreasuryOutErrorMessage> treasuryOutErrorHandler;
    private static final Map<String, Integer> versionRejectionCounts = new ConcurrentHashMap<>();
    private final Map<String, BombSharePromptMessage> pendingBombSharePrompts = new ConcurrentHashMap<>();

    public static ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    private ConnectionManager() {
        super(URI.create(BuildConfig.WS_URL));
        treasurySessionAuthenticator = new TreasurySessionAuthenticator(
                ConnectionManager::joinActiveMinecraftSession,
                treasuryAuthExecutor,
                this::sendTreasuryAuthResponse,
                this::handleTreasuryAuthFailure);
    }

    public static void disconnectForBlockedServer() {
        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope != WynncraftServerPolicy.Scope.BLOCKED) {
            return;
        }

        autoReconnect = false;
        cancelReconnect();

        ConnectionManager current = instance;
        if (current == null) {
            return;
        }

        if (!current.hasConnectionState()) {
            instance = null;
            return;
        }

        boolean shouldNotify = current.isOpen() || current.connectInProgress;
        current.disconnectInternal(false);
        if (shouldNotify) {
            current.notify(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE);
        }
    }

    // ── Connect / Disconnect ──

    @Override
    public void connect() {
        connectInternal(false, false);
    }

    public void connectManually() {
        connectInternal(true, true);
    }

    private void connectInternal(boolean userInitiated, boolean forceTokenRefresh) {
        if (userInitiated) {
            autoConnectSuppressedByManualDisconnect = false;
        }
        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope != WynncraftServerPolicy.Scope.MAIN) {
            invalidateConnectionAttempt();
            connectInProgress = false;
            finishConnectFlow();
            if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
                autoReconnect = false;
                cancelReconnect();
                if (userInitiated) {
                    notify(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE);
                }
                SeqClient.LOGGER.info("[WebSocket] Blocking connection outside main Wynncraft host");
            } else {
                if (userInitiated) {
                    notify("Waiting until Wynncraft server transfer finishes.");
                }
                SeqClient.LOGGER.info("[WebSocket] Delaying connection until Wynncraft host is confirmed");
            }
            return;
        }

        userInitiatedConnectFlow = userInitiated;
        autoReconnect = true;
        SeqClient.LOGGER.info(
                "[WebSocket] connectInternal() called open={} authenticated={} autoReconnect={} configuredUrl={} clientUri={}",
                isOpen(),
                authenticated,
                autoReconnect,
                BuildConfig.WS_URL,
                getURI());
        if (isOpen()) {
            notifyConnectionStatus("Already connected/connecting.");
            finishConnectFlow();
            return;
        }
        if (connectInProgress) {
            notifyConnectionStatus("Already connected/connecting.");
            return;
        }

        notifyConnectionStatus("Connecting to " + BuildConfig.ENVIRONMENT + "...");
        if (shouldUseTreasuryOnlyConnection(currentMinecraftUsername())) {
            prepareTreasuryOnlyConnection();
        } else {
            prepareAuthenticatedConnection(forceTokenRefresh);
        }
    }

    public void disconnect() {
        disconnectInternal(false);
    }

    public void disconnectManually() {
        disconnectInternal(true);
    }

    private void disconnectInternal(boolean userInitiated) {
        if (userInitiated) {
            autoConnectSuppressedByManualDisconnect = true;
        }
        boolean open = isOpen();
        boolean hadConnectionState = hasConnectionState();
        boolean hadAutoReconnect = autoReconnect;
        invalidateConnectionAttempt();
        if (!hadConnectionState && !hadAutoReconnect) {
            connectInProgress = false;
            treasurySessionAuthenticator.reset();
            connectedSince = null;
            instance = null;
            if (userInitiated) {
                notify("Not connected");
            }
            return;
        }

        SeqClient.LOGGER.info(
                "[WebSocket] disconnect() called open={} authenticated={} autoReconnect={}",
                open,
                authenticated,
                autoReconnect);
        finishConnectFlow();
        autoReconnect = false;
        cancelReconnect();
        connectInProgress = false;
        if (!open) {
            authenticated = false;
            authFailed = false;
            notInGuild = false;
            membershipProbePending = false;
            memberFeaturesDisabled = false;
            treasurySessionAuthenticator.reset();
            connectedSince = null;
            instance = null;
            if (userInitiated) {
                notify("Not connected");
            }
            return;
        }
        close();
        authenticated = false;
        authFailed = false;
        notInGuild = false;
        membershipProbePending = false;
        memberFeaturesDisabled = false;
        treasurySessionAuthenticator.reset();
        connectedSince = null;
        if (userInitiated) {
            notify("Disconnected");
        }
    }

    // ── WebSocket lifecycle ──

    @Override
    public void onOpen(ServerHandshake handshake) {
        if (instance != this || !connectInProgress) {
            SeqClient.LOGGER.info("[WebSocket] Closing a superseded websocket connection");
            connectInProgress = false;
            close();
            return;
        }
        SeqClient.LOGGER.info(
                "[WebSocket] onOpen configuredUrl={} clientUri={} status={} message='{}'",
                BuildConfig.WS_URL,
                getURI(),
                handshake != null ? handshake.getHttpStatus() : -1,
                handshake != null ? handshake.getHttpStatusMessage() : "null");
        connectInProgress = false;
        reconnectAttempt = 0;
        authenticated = false;
        authFailed = false;
        notInGuild = false;
        membershipProbePending = false;
        memberFeaturesDisabled = false;
        treasurySessionAuthenticator.reset();
        connectedSince = null;
        autoReconnect = true;
        authAttempt = 0;
        nextAllowedAuthAttemptAtMs = 0;

        if (treasuryOnlyConnection) {
            notifyConnectionStatus("Connected for Treasury OUT. Verifying the cinfrascitizen Minecraft session...");
        } else {
            String username = SeqClient.getConfigManager().getMinecraftUsername();
            notifyConnectionStatus(
                    username != null && !username.isBlank()
                            ? "Connected websocket for " + username + ". Waiting for backend authentication..."
                            : "Connected websocket to " + BuildConfig.ENVIRONMENT
                                    + ". Waiting for backend authentication...");
        }
        if (!treasuryOnlyConnection) {
            finishConnectFlow();
        }
    }

    @Override
    public void onMessage(String message) {
        if (instance != this) {
            SeqClient.LOGGER.debug("[WebSocket] Ignoring message from a superseded websocket connection");
            return;
        }
        SeqClient.LOGGER.debug("[WebSocket] onMessage raw={} chars", message != null ? message.length() : -1);
        handleMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        SeqClient.LOGGER.info(
                "[WebSocket] onClose code={} reason='{}' remote={} autoReconnect={}",
                code,
                reason,
                remote,
                autoReconnect);
        connectInProgress = false;
        authenticated = false;
        authFailed = false;
        notInGuild = false;
        membershipProbePending = false;
        memberFeaturesDisabled = false;
        treasurySessionAuthenticator.reset();
        connectedSince = null;
        if (instance != this) {
            SeqClient.LOGGER.debug("[WebSocket] Ignoring close from a superseded websocket connection");
            return;
        }
        instance = null;
        handleWebSocketAuthRejection(code, reason);
        boolean shouldReconnect = autoReconnect && shouldReconnectAfterClose(code, remote);
        if (shouldReconnect) {
            SeqClient.LOGGER.info(
                    "[WebSocket] Scheduling reconnect after close code={} remote={} reason='{}'", code, remote, reason);
            scheduleReconnect();
        } else {
            SeqClient.LOGGER.info(
                    "[WebSocket] Reconnect not scheduled (autoReconnect={} code={} remote={} reason='{}')",
                    autoReconnect,
                    code,
                    remote,
                    reason);
            finishConnectFlow();
        }
    }

    @Override
    public void onError(Exception ex) {
        SeqClient.LOGGER.error(
                "[WebSocket] onError open={} authenticated={} message={}",
                isOpen(),
                authenticated,
                ex != null ? ex.getMessage() : "null",
                ex);
        if (instance != this) {
            SeqClient.LOGGER.debug("[WebSocket] Ignoring error from a superseded websocket connection");
            return;
        }
        connectInProgress = false;
        authenticated = false;
        treasurySessionAuthenticator.reset();
        connectedSince = null;
        notifyConnectionFailure("Connection error: " + (ex != null ? ex.getMessage() : "unknown"), false);
        finishConnectFlow();
    }

    // ── Auto-reconnect ──

    private static void scheduleReconnect() {
        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
            autoReconnect = false;
            cancelReconnect();
            SeqClient.LOGGER.info("[WebSocket] Auto reconnect suppressed outside main Wynncraft host");
            return;
        }
        if (reconnectAttempt >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            autoReconnect = false;
            cancelReconnect();
            SeqClient.LOGGER.warn("[WebSocket] Auto reconnect exhausted after {} attempts", reconnectAttempt);
            notifyManualConnectRequired();
            return;
        }
        cancelReconnect();
        long delay = Math.min(RECONNECT_BASE_MS * (1L << reconnectAttempt), RECONNECT_CAP_MS);
        reconnectAttempt++;
        SeqClient.LOGGER.info("[WebSocket] Reconnecting in {}ms (attempt {})", delay, reconnectAttempt);
        reconnectTask = scheduler.schedule(
                () -> {
                    instance = null;
                    try {
                        WynncraftServerPolicy.Scope reconnectScope = WynncraftServerPolicy.currentScope();
                        if (reconnectScope == WynncraftServerPolicy.Scope.BLOCKED) {
                            autoReconnect = false;
                            cancelReconnect();
                            SeqClient.LOGGER.info("[WebSocket] Cancelled reconnect because current host is blocked");
                            return;
                        }
                        if (reconnectScope != WynncraftServerPolicy.Scope.MAIN) {
                            reconnectAttempt = Math.max(0, reconnectAttempt - 1);
                            SeqClient.LOGGER.info(
                                    "[WebSocket] Delaying reconnect attempt {} until Wynncraft host is confirmed",
                                    reconnectAttempt);
                            scheduleReconnect();
                            return;
                        }
                        SeqClient.LOGGER.info("[WebSocket] Running scheduled reconnect attempt {}", reconnectAttempt);
                        getInstance().connect();
                    } catch (Exception e) {
                        SeqClient.LOGGER.error("[WebSocket] Reconnect failed", e);
                        scheduleReconnect();
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private static void cancelReconnect() {
        if (reconnectTask != null) {
            SeqClient.LOGGER.debug("[WebSocket] Cancelling pending reconnect task");
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void notifyConnectionStatus(String message) {
        if (userInitiatedConnectFlow) {
            notify(message);
        }
    }

    private void notifyConnectionFailure(String message, boolean requiresManualIntervention) {
        if (requiresManualIntervention || userInitiatedConnectFlow) {
            notify(message);
        }
    }

    private void finishConnectFlow() {
        userInitiatedConnectFlow = false;
    }

    static boolean shouldReconnectAfterClose(int code, boolean remote) {
        if (remote) {
            return true;
        }
        // Local clean close (1000) is treated as intentional; do not auto-reconnect.
        // Handshake/protocol close failures (e.g. 1002 from HTTP 502) should retry.
        return code != 1000;
    }

    private boolean hasConnectionState() {
        return isOpen()
                || connectInProgress
                || authenticated
                || authFailed
                || notInGuild
                || connectedSince != null
                || reconnectTask != null
                || userInitiatedConnectFlow;
    }

    static boolean hasReconnectTask() {
        return reconnectTask != null;
    }

    public static boolean canAutoConnectNow() {
        ConnectionManager current = instance;
        return shouldAttemptAutomaticConnect(
                current != null && current.isOpen(),
                current != null && current.authenticated,
                current != null && current.connectInProgress,
                reconnectTask != null,
                autoConnectSuppressedByManualDisconnect);
    }

    public static boolean isAutoConnectSuppressedByManualDisconnect() {
        return autoConnectSuppressedByManualDisconnect;
    }

    static void resetForTest() {
        pendingGuildWarEvents.clear();
        autoReconnect = true;
        reconnectAttempt = 0;
        autoConnectSuppressedByManualDisconnect = false;
        cancelReconnect();
        instance = null;
    }

    static boolean shouldAttemptAutomaticConnect(
            boolean socketOpen,
            boolean authenticated,
            boolean connectInProgress,
            boolean reconnectScheduled,
            boolean manualDisconnectSuppressed) {
        return !manualDisconnectSuppressed
                && !socketOpen
                && !authenticated
                && !connectInProgress
                && !reconnectScheduled;
    }

    private static void notifyManualConnectRequired() {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                        java.util.Objects.requireNonNull(
                                NotificationAccessor.prefixed(
                                        "Could not reconnect automatically. Run /seq connect manually.")),
                        false);
            }
        });
    }

    // ── Outgoing messages ──

    private boolean send(String type, JsonObject payload) {
        if (isServerScopedType(type) && WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) {
            SeqClient.LOGGER.warn("[WebSocket] Dropping {} outside confirmed main Wynncraft host", type);
            return false;
        }
        if (isAuthenticatedOutboundType(type) && !canSendAuthenticated(type)) {
            return false;
        }
        if (isThrottleLimitedType(type) && !canSendThrottleLimited(type)) {
            return false;
        }
        sendPrepared(type, payload);
        return true;
    }

    private void sendPrepared(String type, JsonObject payload) {
        if (payload == null) payload = new JsonObject();
        payload.addProperty("type", type);
        if (TreasuryOutRequest.TYPE.equals(type)) {
            SeqClient.LOGGER.debug(
                    "[WebSocket] send type={} requestId={}", type, extractPrimitiveString(payload, "request_id"));
        } else {
            SeqClient.LOGGER.debug("[WebSocket] send type={} payload={}", type, truncate(payload.toString(), 512));
        }
        send(GSON.toJson(payload));
    }

    private void prepareAuthenticatedConnection(boolean forceTokenRefresh) {
        treasuryOnlyConnection = false;
        treasurySessionAuthenticator.reset();
        WynncraftServerPolicy.Scope initialScope = WynncraftServerPolicy.currentScope();
        if (initialScope != WynncraftServerPolicy.Scope.MAIN) {
            connectInProgress = false;
            if (initialScope == WynncraftServerPolicy.Scope.BLOCKED) {
                autoReconnect = false;
                cancelReconnect();
                notifyConnectionFailure(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE, false);
            } else if (autoReconnect) {
                scheduleReconnect();
            }
            finishConnectFlow();
            return;
        }
        if (!canAttemptAuthNow()) {
            return;
        }
        long connectionAttempt = beginConnectionAttempt();
        SeqClient.getAuthService()
                .ensureValidToken(forceTokenRefresh)
                .whenComplete((token, throwable) -> Minecraft.getInstance().execute(() -> {
                    if (!isCurrentConnectionAttempt(connectionAttempt)) {
                        return;
                    }
                    if (throwable != null) {
                        connectInProgress = false;
                        AuthException authException = unwrapAuthException(throwable);
                        authFailed = true;
                        authenticated = false;
                        registerAuthFailure();
                        SeqClient.LOGGER.warn(
                                "[WebSocket] Failed to obtain backend auth token code={} message={}",
                                authException.getStableCode(),
                                authException.getMessage(),
                                authException);
                        notifyConnectionFailure(authException.getMessage(), !authException.isRetryable());
                        finishConnectFlow();
                        if (authException.isRetryable() && autoReconnect) {
                            scheduleReconnect();
                        }
                        return;
                    }

                    try {
                        WynncraftServerPolicy.Scope currentScope = WynncraftServerPolicy.currentScope();
                        if (currentScope != WynncraftServerPolicy.Scope.MAIN) {
                            connectInProgress = false;
                            if (currentScope == WynncraftServerPolicy.Scope.BLOCKED) {
                                autoReconnect = false;
                                cancelReconnect();
                                notifyConnectionFailure(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE, false);
                            } else if (autoReconnect) {
                                scheduleReconnect();
                            }
                            finishConnectFlow();
                            return;
                        }
                        configureHandshakeAuthorization(token);
                        super.connect();
                    } catch (Exception exception) {
                        connectInProgress = false;
                        SeqClient.LOGGER.error("Failed to connect", exception);
                        notifyConnectionFailure("Failed to connect: " + exception.getMessage(), false);
                        instance = null;
                        finishConnectFlow();
                        scheduleReconnect();
                    }
                }));
    }

    private synchronized long beginConnectionAttempt() {
        connectInProgress = true;
        return ++connectionAttemptGeneration;
    }

    private synchronized void invalidateConnectionAttempt() {
        connectionAttemptGeneration++;
    }

    private synchronized boolean isCurrentConnectionAttempt(long startedFor) {
        return shouldContinueAuthenticatedConnection(
                startedFor, connectionAttemptGeneration, connectInProgress, instance == this);
    }

    static boolean shouldContinueAuthenticatedConnection(
            long startedFor, long currentGeneration, boolean connectInProgress, boolean activeInstance) {
        return startedFor == currentGeneration && connectInProgress && activeInstance;
    }

    private void prepareTreasuryOnlyConnection() {
        WynncraftServerPolicy.Scope scope = WynncraftServerPolicy.currentScope();
        if (scope != WynncraftServerPolicy.Scope.MAIN) {
            connectInProgress = false;
            finishConnectFlow();
            if (scope == WynncraftServerPolicy.Scope.BLOCKED) {
                autoReconnect = false;
                cancelReconnect();
                notifyConnectionFailure(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE, false);
            } else if (autoReconnect) {
                scheduleReconnect();
            }
            return;
        }

        connectInProgress = true;
        treasuryOnlyConnection = true;
        treasurySessionAuthenticator.reset();
        try {
            configureHandshakeAuthorization(null);
            super.connect();
        } catch (Exception exception) {
            connectInProgress = false;
            SeqClient.LOGGER.error("Failed to connect Treasury OUT websocket", exception);
            notifyConnectionFailure("Failed to connect: " + exception.getMessage(), false);
            instance = null;
            finishConnectFlow();
            scheduleReconnect();
        }
    }

    private void configureHandshakeAuthorization(String token) {
        Map<String, String> headers = buildHandshakeHeaders(token);
        clearHeaders();
        headers.forEach(this::addHeader);
    }

    static Map<String, String> buildHandshakeHeaders(String token) {
        return buildHandshakeHeaders(token, ClientVersion.resolveInstalledVersion());
    }

    static Map<String, String> buildHandshakeHeaders(String token, String modVersion) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", buildAuthorizationHeaderValue(token));
        }
        if (modVersion != null && !modVersion.isBlank()) {
            headers.put(ClientVersion.MOD_VERSION_HEADER, modVersion.trim());
        }
        return headers;
    }

    static String buildAuthorizationHeaderValue(String token) {
        return "Bearer " + token.trim();
    }

    static JsonObject buildGuildWarSubmissionPayload(GuildWarSubmission submission) {
        JsonObject payload = new JsonObject();
        payload.addProperty("territory", submission.territory());
        payload.addProperty("submitted_by", submission.submittedBy());
        payload.addProperty("submitted_at", submission.submittedAt());
        payload.addProperty("start_time", submission.startTime());

        JsonArray warrers = new JsonArray();
        for (String warrer : submission.warrers()) {
            warrers.add(warrer);
        }
        payload.add("warrers", warrers);

        JsonObject damage = new JsonObject();
        damage.addProperty("low", submission.stats().damageLow());
        damage.addProperty("high", submission.stats().damageHigh());

        JsonObject stats = new JsonObject();
        stats.add("damage", damage);
        stats.addProperty("attack", submission.stats().attackSpeed());
        stats.addProperty("health", submission.stats().health());
        stats.addProperty("defence", submission.stats().defence());

        JsonObject results = new JsonObject();
        results.add("stats", stats);
        payload.add("results", results);

        payload.addProperty("sr", submission.seasonRating());
        if (submission.completedAt() != null && !submission.completedAt().isBlank()) {
            payload.addProperty("completed_at", submission.completedAt());
        }
        return payload;
    }

    static JsonObject buildWarStatusPayload(WarStatusUpdate update) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", update.status().name());
        if (update.classType() != null) {
            payload.addProperty("class", update.classType().name());
        }
        if (update.territory() != null) {
            payload.addProperty("territory", update.territory());
        }
        if (update.x() != null && update.z() != null) {
            payload.addProperty("x", update.x());
            payload.addProperty("z", update.z());
        }
        return payload;
    }

    static JsonObject buildWarTowerUpdatePayload(WarTowerUpdate update) {
        JsonObject payload = new JsonObject();
        payload.addProperty("territory", update.territory());
        payload.addProperty("health", update.health());
        payload.addProperty("ehp", update.ehp());
        payload.addProperty("dps", update.dps());
        return payload;
    }

    static JsonObject buildBombShareRequestPayload(String canonicalKey, List<BombShareType> requestedTypes) {
        JsonObject payload = new JsonObject();
        payload.addProperty("canonical_key", canonicalKey);
        JsonArray types = new JsonArray();
        if (requestedTypes != null) {
            for (BombShareType requestedType : requestedTypes) {
                if (requestedType != null) {
                    types.add(requestedType.name());
                }
            }
        }
        payload.add("requested_types", types);
        return payload;
    }

    static JsonObject buildBombShareSubmitPayload(String requestId, List<String> worlds) {
        JsonObject payload = new JsonObject();
        payload.addProperty("request_id", requestId);
        JsonArray worldArray = new JsonArray();
        if (worlds != null) {
            for (String world : worlds) {
                if (world != null && !world.isBlank()) {
                    worldArray.add(world.trim());
                }
            }
        }
        payload.add("worlds", worldArray);
        return payload;
    }

    static JsonObject serializeTreasuryOutRequest(TreasuryOutRequest request) {
        return GSON.toJsonTree(request).getAsJsonObject();
    }

    static JsonObject serializeTreasuryAuthResponse(TreasuryAuthResponse response) {
        return GSON.toJsonTree(response).getAsJsonObject();
    }

    private AuthException unwrapAuthException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof AuthException authException) {
            return authException;
        }
        return new AuthException(
                AuthErrorCode.NETWORK_FAILURE,
                cause != null && cause.getMessage() != null ? cause.getMessage() : "Authentication request failed.",
                true,
                cause);
    }

    private void handleWebSocketAuthRejection(int code, String reason) {
        String normalizedReason = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        boolean rejected = code == 1008
                || normalizedReason.contains("token")
                || normalizedReason.contains("expired")
                || normalizedReason.contains("unauthor")
                || normalizedReason.contains("forbidden")
                || normalizedReason.contains("auth");
        if (!rejected) {
            return;
        }

        authFailed = true;
        SeqClient.getAuthService()
                .invalidateSession(
                        normalizedReason.contains("expired")
                                ? AuthErrorCode.TOKEN_EXPIRED
                                : AuthErrorCode.WEBSOCKET_AUTH_REJECTED,
                        normalizedReason.contains("expired")
                                ? "Backend token expired. Re-authenticating."
                                : "Backend rejected websocket authentication. Re-authenticating.");
        notifyConnectionFailure(SeqClient.getAuthService().getLastError().getMessage(), false);
        registerAuthFailure();
    }

    public void requestConnectedUsers(Consumer<List<String>> callback) {
        SeqClient.LOGGER.info("[WebSocket] requestConnectedUsers open={} authenticated={}", isOpen(), authenticated);
        if (!isOpen()
                || !authenticated
                || authFailed
                || notInGuild
                || memberFeaturesDisabled
                || !WynncraftServerPolicy.isCurrentServerAllowed()) {
            callback.accept(List.of());
            return;
        }
        connectedUsersCallbacks.add(callback);
        send("get_connected", null);
    }

    public boolean sendBombShareRequest(String canonicalKey, List<BombShareType> requestedTypes) {
        if (!isOpen() || !authenticated || authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendBombShareRequest dropped open={} authenticated={} authFailed={} notInGuild={}",
                    isOpen(),
                    authenticated,
                    authFailed,
                    notInGuild);
            return false;
        }
        if (canonicalKey == null || canonicalKey.isBlank() || requestedTypes == null || requestedTypes.isEmpty()) {
            SeqClient.LOGGER.warn("[WebSocket] sendBombShareRequest dropped invalid payload canonicalKey={}", canonicalKey);
            return false;
        }

        send("bomb_share_request", buildBombShareRequestPayload(canonicalKey, requestedTypes));
        return true;
    }

    public boolean sendBombShareSubmit(String requestId, List<String> worlds) {
        if (!isOpen() || !authenticated || authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendBombShareSubmit dropped open={} authenticated={} authFailed={} notInGuild={}",
                    isOpen(),
                    authenticated,
                    authFailed,
                    notInGuild);
            return false;
        }
        if (requestId == null || requestId.isBlank() || worlds == null || worlds.isEmpty()) {
            SeqClient.LOGGER.warn("[WebSocket] sendBombShareSubmit dropped invalid payload requestId={}", requestId);
            return false;
        }

        send("bomb_share_submit", buildBombShareSubmitPayload(requestId, worlds));
        return true;
    }

    public boolean sendTreasuryOut(TreasuryOutRequest request) {
        if (!TreasuryOutManager.isTreasuryMinecraftAccount(currentMinecraftUsername())) {
            SeqClient.LOGGER.warn("[WebSocket] sendTreasuryOut dropped because active account is not authorized");
            return false;
        }
        if (request == null
                || request.requestId() == null
                || request.requestId().isBlank()
                || request.amount() == null
                || request.amount().isBlank()
                || request.payouter() == null
                || request.payouter().isBlank()
                || request.reason() == null
                || request.reason().isBlank()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendTreasuryOut dropped invalid payload requestId={}",
                    request == null ? null : request.requestId());
            return false;
        }
        if (!isOpen() || !treasurySessionAuthenticator.isAuthenticated()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendTreasuryOut dropped because Treasury session is not verified requestId={}",
                    request.requestId());
            return false;
        }
        return send(TreasuryOutRequest.TYPE, serializeTreasuryOutRequest(request));
    }

    public void sendGuildChat(String username, String nickname, String message, String avatarUrl) {
        sendGuildChat(username, nickname, message, avatarUrl, List.of());
    }

    public void sendGuildChat(
            String username, String nickname, String message, String avatarUrl, List<ChatItemPreview> itemPreviews) {
        if (!isOpen()) {
            SeqClient.LOGGER.warn("[ConnectionManager] sendGuildChat dropped: socket not open uri={}", getURI());
            return;
        }
        if (!authenticated) {
            SeqClient.LOGGER.warn("[ConnectionManager] sendGuildChat dropped: not authenticated uri={}", getURI());
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[ConnectionManager] sendGuildChat dropped: authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }

        String cleanedMessage = message == null ? "" : message.trim();
        if (cleanedMessage.isBlank()) {
            SeqClient.LOGGER.warn("[ConnectionManager] sendGuildChat dropped: empty message");
            return;
        }
        JsonObject msg;
        try {
            msg = buildGuildChatPayload(username, nickname, cleanedMessage, avatarUrl, itemPreviews);
        } catch (IllegalArgumentException exception) {
            SeqClient.LOGGER.warn(
                    "[ConnectionManager] sendGuildChat dropped: message too long={} max={}",
                    cleanedMessage.length(),
                    MAX_GUILD_CHAT_MESSAGE_LENGTH);
            notify("Guild chat message too long.");
            return;
        }
        SeqClient.LOGGER.info(
                "[ConnectionManager] Sending guild_chat uri={} username='{}' nickname='{}' message='{}'",
                getURI(),
                extractPrimitiveString(msg, "username"),
                extractPrimitiveString(msg, "nickname"),
                cleanedMessage);
        send("guild_chat", msg);
    }

    /** Builds the wire payload used by sendGuildChat, before session/throttle checks. */
    static JsonObject buildGuildChatPayload(
            String username, String nickname, String message, String avatarUrl, List<ChatItemPreview> itemPreviews) {
        String cleanedMessage = message == null ? "" : message.trim();
        if (cleanedMessage.isBlank()) {
            throw new IllegalArgumentException("Guild chat message is required");
        }
        if (cleanedMessage.length() > MAX_GUILD_CHAT_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Guild chat message is too long");
        }
        String safeAvatarUrl = sanitizeAvatarUrl(avatarUrl);
        String safeReportedUsername = sanitizeMinecraftUsername(username);
        String safeNickname = sanitizeNickname(nickname);
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "guild_chat");
        msg.addProperty("should_be_displayed", false);
        if (safeReportedUsername != null) {
            msg.addProperty("username", safeReportedUsername);
        }
        if (safeNickname != null) {
            msg.addProperty("nickname", safeNickname);
        }
        msg.addProperty("message", cleanedMessage);
        if (safeAvatarUrl != null) msg.addProperty("avatar_url", safeAvatarUrl);
        JsonArray itemPreviewArray = itemPreviewArray(itemPreviews);
        if (itemPreviewArray.size() > 0) {
            msg.add("item_previews", itemPreviewArray);
        }
        return msg;
    }

    static boolean shouldDisplayGuildChat(JsonObject json) {
        var flag = json.get("should_be_displayed");
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()
                && flag.getAsBoolean();
    }

    static GuildChatMessage parseGuildChatMessage(JsonObject json) {
        if (!shouldDisplayGuildChat(json)) return null;
        String username = guildChatString(json, "username");
        String message = guildChatString(json, "message");
        if (username == null || message == null) return null;
        return new GuildChatMessage(username, guildChatString(json, "nickname"), message);
    }

    private static String guildChatString(JsonObject json, String key) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    public boolean sendGuildAllianceUpdate(String action, String guildName) {
        String safeAction = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        String safeGuildName = guildName == null ? "" : guildName.trim();
        if (!"formed".equals(safeAction) && !"revoked".equals(safeAction)) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildAllianceUpdate dropped invalid action={}", action);
            return false;
        }
        if (safeGuildName.isEmpty() || safeGuildName.length() > 64) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildAllianceUpdate dropped invalid guild='{}'", guildName);
            return false;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("action", safeAction);
        msg.addProperty("guild_name", safeGuildName);
        send("guild_alliance_update", msg);
        return true;
    }

    public static void observeGuildRankEvent(GuildRankEventParser.Event event) {
        Minecraft client = Minecraft.getInstance();
        if (WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN || client.getUser() == null) return;
        SeqClient.LOGGER.debug(
                "[GuildRank] Observed assignment actor='{}' target='{}' oldRank='{}' newRank='{}'",
                event.actor().displayName(), event.target().displayName(), event.oldRank(), event.newRank());
        guildRankObservations.add(event, client.getUser().getProfileId(), Instant.now());
        tickGuildRankObservations();
    }

    public static void tickGuildRankObservations() {
        ConnectionManager current = instance;
        Minecraft client = Minecraft.getInstance();
        if (current == null || !current.guildRankTrackingSupported || !current.isOpen() || !current.authenticated
                || client.getUser() == null || current.membershipProbePending || current.memberFeaturesDisabled
                || WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) return;
        Instant now = Instant.now();
        for (JsonObject payload : guildRankObservations.due(client.getUser().getProfileId(), now)) {
            if (current.send("guild_rank_event", payload)) {
                guildRankObservations.sent(payload.get("observation_id").getAsString(), now);
                SeqClient.LOGGER.info("[GuildRank] Sent observation id={}", payload.get("observation_id").getAsString());
            }
        }
    }

    public boolean sendGuildMembershipEvent(String action, String actor, String target) {
        String safeAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        String safeActor = sanitizeMinecraftUsername(actor);
        String safeTarget = sanitizeMinecraftUsername(target);
        if ((!"invited".equals(safeAction) && !"uninvited".equals(safeAction))
                || safeActor == null
                || safeTarget == null) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildMembershipEvent dropped invalid action='{}' actor='{}' target='{}'",
                    action,
                    actor,
                    target);
            return false;
        }

        return send("guild_membership_event", buildGuildMembershipEventPayload(safeAction, safeActor, safeTarget));
    }

    static JsonObject buildGuildMembershipEventPayload(String action, String actor, String target) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", action);
        payload.addProperty("actor", actor);
        payload.addProperty("target", target);
        return payload;
    }

    public boolean sendGuildAllianceSnapshot(Collection<String> guildNames) {
        List<String> safeGuildNames = normalizeGuildAllianceNames(guildNames);
        if (safeGuildNames == null) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildAllianceSnapshot dropped invalid guild list");
            return false;
        }

        return send("guild_alliance_snapshot", buildGuildAllianceSnapshotPayload(safeGuildNames));
    }

    static JsonObject buildGuildAllianceSnapshotPayload(List<String> guildNames) {
        JsonArray names = new JsonArray();
        for (String guildName : guildNames) {
            names.add(guildName);
        }

        JsonObject payload = new JsonObject();
        payload.add("guild_names", names);
        return payload;
    }

    static List<String> normalizeGuildAllianceNames(Collection<String> guildNames) {
        if (guildNames == null) {
            return null;
        }

        Map<String, String> uniqueNames = new LinkedHashMap<>();
        for (String guildName : guildNames) {
            if (guildName == null) {
                return null;
            }
            String trimmedName = guildName.trim();
            if (trimmedName.isEmpty() || trimmedName.length() > 64 || trimmedName.contains(":")) {
                return null;
            }
            uniqueNames.putIfAbsent(trimmedName.toLowerCase(Locale.ROOT), trimmedName);
        }
        if (uniqueNames.size() > 16) {
            return null;
        }
        return List.copyOf(uniqueNames.values());
    }

    static JsonArray itemPreviewArray(List<ChatItemPreview> itemPreviews) {
        JsonArray previews = new JsonArray();
        if (itemPreviews == null || itemPreviews.isEmpty()) {
            return previews;
        }
        for (ChatItemPreview preview : itemPreviews) {
            if (preview == null || preview.name() == null || preview.name().isBlank()) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("name", preview.name());
            if (preview.subtitle() != null && !preview.subtitle().isBlank()) {
                json.addProperty("subtitle", preview.subtitle());
            }
            if (preview.color() != null) {
                json.addProperty("color", preview.color());
            }
            JsonArray attributes = stringArray(preview.attributes());
            if (attributes.size() > 0) {
                json.add("attributes", attributes);
            }
            JsonArray statLines = stringArray(preview.statLines());
            if (statLines.size() > 0) {
                json.add("stat_lines", statLines);
            }
            JsonArray statRolls = statRollArray(preview.statRolls());
            if (statRolls.size() > 0) {
                json.add("stat_rolls", statRolls);
            }
            if (preview.shinyStat() != null) {
                json.add("shiny_stat", shinyStatJson(preview.shinyStat()));
            }
            JsonArray sections = itemSectionArray(preview.sections());
            if (sections.size() > 0) {
                json.add("sections", sections);
            }
            previews.add(json);
        }
        return previews;
    }

    private static JsonArray itemSectionArray(List<ChatItemPreview.Section> sections) {
        JsonArray array = new JsonArray();
        if (sections == null || sections.isEmpty()) {
            return array;
        }
        for (ChatItemPreview.Section section : sections) {
            if (section == null || section.title() == null || section.title().isBlank()) {
                continue;
            }
            JsonArray lines = stringArray(section.lines());
            if (lines.size() == 0) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("title", section.title());
            json.add("lines", lines);
            array.add(json);
        }
        return array;
    }

    private static JsonObject shinyStatJson(ChatItemPreview.ShinyStat shinyStat) {
        JsonObject json = new JsonObject();
        if (shinyStat.key() != null && !shinyStat.key().isBlank()) {
            json.addProperty("key", shinyStat.key());
        }
        if (shinyStat.displayName() != null && !shinyStat.displayName().isBlank()) {
            json.addProperty("display_name", shinyStat.displayName());
        }
        json.addProperty("value", shinyStat.value());
        json.addProperty("rerolls", shinyStat.rerolls());
        return json;
    }

    private static JsonArray statRollArray(List<ChatItemPreview.StatRoll> statRolls) {
        JsonArray array = new JsonArray();
        if (statRolls == null || statRolls.isEmpty()) {
            return array;
        }
        for (ChatItemPreview.StatRoll statRoll : statRolls) {
            if (statRoll == null || statRoll.percentage() == null) {
                continue;
            }
            JsonObject json = new JsonObject();
            if (statRoll.apiName() != null && !statRoll.apiName().isBlank()) {
                json.addProperty("api_name", statRoll.apiName());
            }
            if (statRoll.key() != null && !statRoll.key().isBlank()) {
                json.addProperty("key", statRoll.key());
            }
            if (statRoll.displayName() != null && !statRoll.displayName().isBlank()) {
                json.addProperty("display_name", statRoll.displayName());
            }
            json.addProperty("value", statRoll.value());
            json.addProperty("percentage", statRoll.percentage());
            array.add(json);
        }
        return array;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        return array;
    }

    public void sendRaidAnnouncement(
            List<String> usernames,
            String raidType,
            int aspectCount,
            int emeraldCount,
            double experienceCount,
            int srCount,
            Map<String, Integer> gambitCounts) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendRaidAnnouncement dropped open={} authenticated={}", isOpen(), authenticated);
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendRaidAnnouncement dropped authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }
        if (usernames == null || usernames.isEmpty() || raidType == null || raidType.isBlank()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendRaidAnnouncement dropped: invalid payload usernames={} raidType='{}' aspects={} emeralds={} experience={} sr={}",
                    usernames,
                    raidType,
                    aspectCount,
                    emeraldCount,
                    experienceCount,
                    srCount);
            return;
        }
        SeqClient.LOGGER.info(
                "[WebSocket] Sending guild_raid_announcement type={} usernames={} payloadMembers={}",
                raidType,
                usernames.size(),
                usernames);
        JsonObject msg = buildRaidAnnouncementPayload(
                usernames, raidType, aspectCount, emeraldCount, experienceCount, srCount, gambitCounts);
        send("guild_raid_announcement", msg);
    }

    static JsonObject buildRaidAnnouncementPayload(
            List<String> usernames,
            String raidType,
            int aspectCount,
            int emeraldCount,
            double experienceCount,
            int srCount,
            Map<String, Integer> gambitCounts) {
        JsonObject msg = new JsonObject();
        JsonArray names = new JsonArray();
        usernames.forEach(names::add);
        msg.add("usernames", names);
        msg.addProperty("raid_type", raidType);
        msg.addProperty("aspect_count", aspectCount);
        msg.addProperty("emerald_count", emeraldCount);
        msg.addProperty("experience_count", experienceCount);
        msg.addProperty("sr_count", srCount);
        if (gambitCounts != null && !gambitCounts.isEmpty()) {
            JsonObject counts = new JsonObject();
            gambitCounts.forEach(counts::addProperty);
            msg.add("gambit_counts", counts);
        }
        return msg;
    }

    public void sendGuildBankEvent(
            String action,
            String player,
            Integer quantity,
            String itemName,
            String charges,
            String accessTier,
            String rawMessage) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildBankEvent dropped open={} authenticated={}", isOpen(), authenticated);
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildBankEvent dropped authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }
        if (action == null || action.isBlank() || player == null || player.isBlank() || itemName == null
                || itemName.isBlank() || accessTier == null || accessTier.isBlank() || rawMessage == null
                || rawMessage.isBlank()) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildBankEvent dropped: invalid payload");
            return;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("action", action);
        msg.addProperty("player", player.trim());
        if (quantity != null) {
            msg.addProperty("quantity", quantity);
        }
        msg.addProperty("item_name", itemName.trim());
        if (charges != null && !charges.isBlank()) {
            msg.addProperty("charges", charges.trim());
        }
        msg.addProperty("access_tier", accessTier.trim());
        msg.addProperty("raw_message", rawMessage.trim());
        SeqClient.LOGGER.info(
                "[WebSocket] Sending guild_bank_event action={} player='{}' item='{}'",
                action,
                player,
                itemName);
        send("guild_bank_event", msg);
    }

    public void sendGuildStorageSnapshot(long emeraldCurrent, long emeraldMax, long aspectCurrent, long aspectMax) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageSnapshot dropped open={} authenticated={}", isOpen(), authenticated);
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageSnapshot dropped authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }
        if (emeraldCurrent < 0 || emeraldMax <= 0 || aspectCurrent < 0 || aspectMax <= 0) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageSnapshot dropped invalid payload emerald={}/{} aspect={}/{}",
                    emeraldCurrent,
                    emeraldMax,
                    aspectCurrent,
                    aspectMax);
            return;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("emerald_current", emeraldCurrent);
        msg.addProperty("emerald_max", emeraldMax);
        msg.addProperty("aspect_current", aspectCurrent);
        msg.addProperty("aspect_max", aspectMax);
        SeqClient.LOGGER.info(
                "[WebSocket] Sending guild_storage_snapshot emerald={}/{} aspect={}/{}",
                emeraldCurrent,
                emeraldMax,
                aspectCurrent,
                aspectMax);
        send("guild_storage_snapshot", msg);
    }

    public void sendGuildStorageReward(
            String senderUsername,
            String recipientUsername,
            String resourceType,
            long amount,
            int count,
            Instant windowStartedAt) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageReward dropped open={} authenticated={}", isOpen(), authenticated);
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageReward dropped authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }

        String safeSender = sanitizeMinecraftUsername(senderUsername);
        String safeRecipient = sanitizeMinecraftUsername(recipientUsername);
        String normalizedResourceType = resourceType == null ? "" : resourceType.trim().toLowerCase(Locale.ROOT);
        if (safeSender == null
                || safeRecipient == null
                || count <= 0
                || amount <= 0
                || windowStartedAt == null
                || (!normalizedResourceType.equals("emeralds") && !normalizedResourceType.equals("aspects"))) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendGuildStorageReward dropped invalid payload sender='{}' recipient='{}' resource='{}' amount={} count={} windowStartedAt={}",
                    senderUsername,
                    recipientUsername,
                    resourceType,
                    amount,
                    count,
                    windowStartedAt);
            return;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("sender_username", safeSender);
        msg.addProperty("recipient_username", safeRecipient);
        msg.addProperty("resource_type", normalizedResourceType);
        msg.addProperty("amount", amount);
        msg.addProperty("count", count);
        msg.addProperty("window_started_at", windowStartedAt.toString());
        SeqClient.LOGGER.info(
                "[WebSocket] Sending guild_storage_reward sender='{}' recipient='{}' resource='{}' amount={} count={} windowStartedAt={}",
                safeSender,
                safeRecipient,
                normalizedResourceType,
                amount,
                count,
                windowStartedAt);
        send("guild_storage_reward", msg);
    }

    public boolean sendGuildWarSubmission(GuildWarSubmission submission) {
        if (submission == null
                || submission.territory() == null
                || submission.territory().isBlank()
                || submission.submittedBy() == null
                || submission.submittedBy().isBlank()
                || submission.submittedAt() == null
                || submission.submittedAt().isBlank()
                || submission.startTime() == null
                || submission.startTime().isBlank()
                || submission.warrers() == null
                || submission.warrers().isEmpty()
                || submission.stats() == null) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarSubmission dropped: invalid payload");
            return false;
        }

        for (String warrer : submission.warrers()) {
            if (warrer == null || !MC_USERNAME_PATTERN.matcher(warrer).matches()) {
                SeqClient.LOGGER.warn("[WebSocket] sendGuildWarSubmission dropped invalid warrer={}", warrer);
                return false;
            }
        }

        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarSubmission dropped outside main Wynncraft host");
            return false;
        }
        if (memberFeaturesDisabled) {
            SeqClient.LOGGER.debug("[WebSocket] Guild war submission disabled for non-member session");
            return true;
        }
        return enqueueGuildWarEvent("guild_war_submission", buildGuildWarSubmissionPayload(submission));
    }

    public boolean sendGuildWarQueue(GuildWarQueueSubmission submission) {
        if (submission == null
                || submission.territory() == null
                || submission.territory().isBlank()
                || submission.submittedBy() == null
                || submission.submittedBy().isBlank()
                || submission.submittedAt() == null
                || submission.submittedAt().isBlank()
                || submission.defenseRating() == null
                || submission.defenseRating().isBlank()
                || submission.queueMinutes() < 1
                || submission.queueMinutes() > 60) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarQueue dropped: invalid payload");
            return false;
        }

        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarQueue dropped outside main Wynncraft host");
            return false;
        }
        if (memberFeaturesDisabled) {
            SeqClient.LOGGER.debug("[WebSocket] Guild war queue submission disabled for non-member session");
            return true;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("territory", submission.territory());
        msg.addProperty("submitted_by", submission.submittedBy());
        msg.addProperty("submitted_at", submission.submittedAt());
        msg.addProperty("defense_rating", submission.defenseRating());
        msg.addProperty("queue_minutes", submission.queueMinutes());

        return enqueueGuildWarEvent("guild_war_queue", msg);
    }

    public boolean sendGuildWarQueueCancellation(GuildWarQueueCancellation cancellation) {
        if (cancellation == null
                || cancellation.territory() == null
                || cancellation.territory().isBlank()
                || cancellation.submittedBy() == null
                || cancellation.submittedBy().isBlank()
                || cancellation.submittedAt() == null
                || cancellation.submittedAt().isBlank()) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarQueueCancellation dropped: invalid payload");
            return false;
        }

        WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
        if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
            SeqClient.LOGGER.warn("[WebSocket] sendGuildWarQueueCancellation dropped outside main Wynncraft host");
            return false;
        }
        if (memberFeaturesDisabled) {
            SeqClient.LOGGER.debug("[WebSocket] Guild war queue cancellation disabled for non-member session");
            return true;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("territory", cancellation.territory());
        msg.addProperty("submitted_by", cancellation.submittedBy());
        msg.addProperty("submitted_at", cancellation.submittedAt());
        return enqueueGuildWarEvent("guild_war_queue_cancel", msg);
    }

    public boolean sendWarStatus(WarStatusUpdate update) {
        if (update == null
                || (update.status() != WarStatusUpdate.Status.REMOVE && update.classType() == null)
                || !isReadyForLiveWarTelemetry()
                || WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) {
            return false;
        }
        return tryLiveTelemetrySend(() -> send("war_status", buildWarStatusPayload(update)));
    }

    public boolean sendWarTowerUpdate(WarTowerUpdate update) {
        if (update == null
                || update.territory() == null
                || update.territory().isBlank()
                || !Float.isFinite(update.health())
                || update.health() < 0.0f
                || update.health() > 1.0f
                || update.ehp() < 0L
                || update.dps() < 0L
                || !isReadyForLiveWarTelemetry()
                || WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) {
            return false;
        }
        return tryLiveTelemetrySend(() -> send("war_tower_update", buildWarTowerUpdatePayload(update)));
    }

    static boolean tryLiveTelemetrySend(BooleanSupplier sendAction) {
        try {
            return sendAction.getAsBoolean();
        } catch (RuntimeException exception) {
            // The socket can close after the readiness check but before Java-WebSocket
            // queues the frame. Treat that race like an ordinary failed send so the
            // bounded tracker retry handles it on a later tick.
            SeqClient.LOGGER.debug("[WebSocket] Live war telemetry send raced with connection closure", exception);
            return false;
        }
    }

    private boolean isReadyForLiveWarTelemetry() {
        return isOpen()
                && authenticated
                && !authFailed
                && !notInGuild
                && !memberFeaturesDisabled;
    }

    public static void flushPendingOutbound() {
        if (instance == null) {
            return;
        }
        instance.flushPendingGuildWarEvents();
    }

    public static void resetForAccountChange() {
        pendingGuildWarEvents.clear();
        ConnectionManager current = instance;
        if (current == null) {
            return;
        }

        current.pendingBombSharePrompts.clear();
        current.disconnectInternal(false);
    }

    private boolean enqueueGuildWarEvent(String type, JsonObject payload) {
        if (!pendingGuildWarEvents.offer(type, payload)) {
            SeqClient.LOGGER.warn("[WebSocket] War event buffer full; could not retain {} territory='{}'",
                    type, payload.get("territory").getAsString());
            return false;
        }
        SeqClient.LOGGER.info("[WebSocket] Buffered {} territory='{}' submittedAt={}",
                type, payload.get("territory").getAsString(), payload.get("submitted_at").getAsString());
        flushPendingGuildWarEvents();
        // Accepted for delivery, even when the socket or throttle prevents an immediate send.
        return true;
    }

    private void flushPendingGuildWarEvents() {
        pendingGuildWarEvents.flushOne(this::sendGuildWarEventNow);
    }

    private boolean sendGuildWarEventNow(String type, JsonObject payload) {
        if (instance != this
                || !isReadyForLiveWarTelemetry()
                || WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) {
            return false;
        }
        return tryGuildWarEventSend(() -> {
            if (!send(type, payload)) {
                return false;
            }
            SeqClient.LOGGER.info("[WebSocket] Sent buffered {} territory='{}' submittedAt={}",
                    type, payload.get("territory").getAsString(), payload.get("submitted_at").getAsString());
            return true;
        });
    }

    static boolean tryGuildWarEventSend(BooleanSupplier sendAction) {
        try {
            return sendAction.getAsBoolean();
        } catch (WebsocketNotConnectedException exception) {
            SeqClient.LOGGER.debug("[WebSocket] Retaining war event after connection closed during send", exception);
            return false;
        }
    }

    public void sendPartyClassUpdate(WynnClassType classType) {
        if (!authenticated || !isOpen() || classType == null) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartyClassUpdate dropped open={} authenticated={} classType={}",
                    isOpen(),
                    authenticated,
                    classType);
            return;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartyClassUpdate dropped authFailed={} notInGuild={}", authFailed, notInGuild);
            return;
        }
        SeqClient.LOGGER.info("[WebSocket] Sending party_class_update classType={}", classType);
        JsonObject msg = new JsonObject();
        msg.addProperty("class_type", classType.name());
        send("party_class_update", msg);
    }

    public boolean sendPartySyncSnapshot(
            long listingId, boolean active, String leaderUsername, List<String> memberUsernames) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartySyncSnapshot dropped open={} authenticated={}",
                    isOpen(),
                    authenticated);
            return false;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartySyncSnapshot dropped authFailed={} notInGuild={}",
                    authFailed,
                    notInGuild);
            return false;
        }

        JsonObject msg = buildPartySyncSnapshotPayload(listingId, active, leaderUsername, memberUsernames);
        SeqClient.LOGGER.info(
                "[WebSocket] Sending party_sync_snapshot listingId={} active={} leader={} members={} usernames={}",
                listingId,
                active,
                leaderUsername,
                msg.getAsJsonArray("member_usernames").size(),
                memberUsernames);
        return send("party_sync_snapshot", msg);
    }

    static JsonObject buildPartySyncSnapshotPayload(
            long listingId, boolean active, String leaderUsername, List<String> memberUsernames) {
        if (listingId <= 0L) {
            throw new IllegalArgumentException("listingId must be positive");
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("listing_id", listingId);
        msg.addProperty("active", active);
        if (leaderUsername != null && !leaderUsername.isBlank()) {
            msg.addProperty("leader_username", leaderUsername);
        }
        JsonArray usernames = new JsonArray();
        if (memberUsernames != null) {
            for (String memberUsername : memberUsernames) {
                if (memberUsername != null && !memberUsername.isBlank()) {
                    usernames.add(memberUsername);
                }
            }
        }
        msg.add("member_usernames", usernames);
        return msg;
    }

    public boolean sendPartySyncMemberRemoved(String username, String reason) {
        if (!authenticated || !isOpen()) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartySyncMemberRemoved dropped open={} authenticated={}",
                    isOpen(),
                    authenticated);
            return false;
        }
        if (authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] sendPartySyncMemberRemoved dropped authFailed={} notInGuild={}",
                    authFailed,
                    notInGuild);
            return false;
        }
        if (username == null || username.isBlank() || reason == null || reason.isBlank()) {
            SeqClient.LOGGER.warn("[WebSocket] sendPartySyncMemberRemoved dropped invalid payload");
            return false;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("username", username);
        msg.addProperty("reason", reason);
        SeqClient.LOGGER.info("[WebSocket] Sending party_sync_member_removed username={} reason={}", username, reason);
        return send("party_sync_member_removed", msg);
    }

    public void sendLocalPartyClassUpdate() {
        WynnClassType classType = WynnClassCache.resolveLocalClassType();
        if (classType == null) {
            return;
        }
        sendPartyClassUpdate(classType);
    }

    // ── Incoming message handler ──

    private void handleMessage(String message) {
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (json == null || !json.has("type")) {
                SeqClient.LOGGER.warn("[WebSocket] Dropping message without type: {}", truncate(message, 512));
                return;
            }
            String type = json.get("type").getAsString();
            if ("treasury_out_recorded".equals(type)
                    || TreasuryAuthResponse.CHALLENGE_TYPE.equals(type)
                    || TreasuryAuthResponse.AUTHENTICATED_TYPE.equals(type)) {
                SeqClient.LOGGER.debug(
                        "[WebSocket] Received message type={} requestId={}",
                        type,
                        extractPrimitiveString(json, "request_id"));
            } else {
                SeqClient.LOGGER.info("[WebSocket] Received message type={} payload={}", type, truncate(message, 512));
            }

            switch (type) {
                case TreasuryAuthResponse.CHALLENGE_TYPE -> {
                    if (!treasuryOnlyConnection) {
                        SeqClient.LOGGER.warn("[TreasuryAuth] Ignoring challenge on an authenticated WebSocket");
                        return;
                    }
                    String nonce = extractPrimitiveString(json, "nonce");
                    if (!treasurySessionAuthenticator.handleChallenge(nonce)) {
                        SeqClient.LOGGER.warn("[TreasuryAuth] Ignoring invalid or unexpected challenge");
                    }
                }
                case TreasuryAuthResponse.AUTHENTICATED_TYPE -> {
                    String nonce = extractPrimitiveString(json, "nonce");
                    if (!treasuryOnlyConnection || !treasurySessionAuthenticator.confirm(nonce)) {
                        SeqClient.LOGGER.warn("[TreasuryAuth] Ignoring uncorrelated authentication confirmation");
                        return;
                    }
                    authFailed = false;
                    connectedSince = Instant.now();
                    autoReconnect = true;
                    notifyConnectionStatus("Treasury OUT identity verified as cinfrascitizen.");
                    finishConnectFlow();
                    SeqClient.LOGGER.info("[TreasuryAuth] Minecraft session proof accepted by backend");
                }
                case "authenticated" -> {
                    if (treasuryOnlyConnection) {
                        SeqClient.LOGGER.warn(
                                "[TreasuryAuth] Ignoring bearer-authenticated response on Treasury-only WebSocket");
                        return;
                    }
                    authenticated = true;
                    guildRankTrackingSupported = json.has("guild_rank_tracking_supported")
                            && json.get("guild_rank_tracking_supported").isJsonPrimitive()
                            && json.get("guild_rank_tracking_supported").getAsBoolean();
                    if (guildRankTrackingSupported) {
                        SeqClient.LOGGER.info("[GuildRank] Backend supports rank tracking");
                    } else {
                        SeqClient.LOGGER.warn(
                                "[GuildRank] Backend did not advertise rank tracking; observations are held for up to "
                                        + "10 minutes. Reconnect after the backend deployment finishes.");
                    }
                    authFailed = false;
                    notInGuild = false;
                    memberFeaturesDisabled = false;
                    membershipProbePending = true;
                    authAttempt = 0;
                    nextAllowedAuthAttemptAtMs = 0;
                    connectedSince = Instant.now();
                    autoReconnect = true;
                    if (json.has("discord_username")
                            && json.get("discord_username").isJsonPrimitive()) {
                        String discordUser = json.get("discord_username").getAsString();
                        storeDiscordUsername(discordUser);
                        notifyConnectionStatus("Connected as " + discordUser);
                    } else {
                        clearDiscordUsername();
                    }
                    sendPrepared("get_connected", null);
                    flushPendingGuildWarEvents();
                    sendLocalPartyClassUpdate();
                }
                case "guild_rank_recorded" -> {
                    String observationId = extractPrimitiveString(json, "observation_id");
                    guildRankObservations.acknowledge(observationId);
                    SeqClient.LOGGER.info("[GuildRank] Backend recorded observation id={}", observationId);
                }
                case "connected_users" -> {
                    boolean wasMembershipProbe = membershipProbePending;
                    membershipProbePending = false;
                    memberFeaturesDisabled = false;
                    List<String> users = new ArrayList<>();
                    json.getAsJsonArray("users").forEach(el -> users.add(el.getAsString()));
                    SeqClient.LOGGER.info(
                            "[WebSocket] connected_users received count={} waiting={}",
                            users.size(),
                            connectedUsersCallbacks.size());
                    boolean delivered = false;
                    Consumer<List<String>> waiting;
                    while ((waiting = connectedUsersCallbacks.poll()) != null) {
                        delivered = true;
                        waiting.accept(users);
                    }
                    if (!delivered && !wasMembershipProbe) {
                        SeqClient.LOGGER.warn("[WebSocket] connected_users had no callback listener");
                    }
                }
                case "bomb_share_prompt" -> {
                    List<BombShareType> requestedTypes =
                            parseBombShareTypes(json.has("requested_types") ? json.getAsJsonArray("requested_types") : null);
                    BombSharePromptMessage prompt = new BombSharePromptMessage(
                            extractPrimitiveString(json, "request_id"),
                            extractPrimitiveString(json, "requester_username"),
                            extractPrimitiveString(json, "canonical_key"),
                            requestedTypes,
                            json.has("expires_at") && !json.get("expires_at").isJsonNull()
                                    ? Instant.parse(json.get("expires_at").getAsString())
                                    : null,
                            json.has("first_prompt")
                                    && json.get("first_prompt").isJsonPrimitive()
                                    && json.get("first_prompt").getAsBoolean());
                    if (prompt.requestId() != null) {
                        pendingBombSharePrompts.put(prompt.requestId(), prompt);
                    }
                    if (bombSharePromptHandler != null) {
                        bombSharePromptHandler.accept(prompt);
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received bomb_share_prompt but handler is not registered");
                    }
                }
                case "bomb_share_result" -> {
                    BombShareResultMessage result = new BombShareResultMessage(
                            extractPrimitiveString(json, "request_id"),
                            extractPrimitiveString(json, "canonical_key"),
                            parseBombShareTypes(json.has("requested_types") ? json.getAsJsonArray("requested_types") : null),
                            parsePrimitiveStringArray(json.has("worlds") ? json.getAsJsonArray("worlds") : null),
                            json.has("share_count") && json.get("share_count").isJsonPrimitive()
                                    ? json.get("share_count").getAsInt()
                                    : 0);
                    if (result.requestId() != null) {
                        pendingBombSharePrompts.remove(result.requestId());
                    }
                    if (bombShareResultHandler != null) {
                        bombShareResultHandler.accept(result);
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received bomb_share_result but handler is not registered");
                    }
                }
                case "treasury_out_recorded" -> {
                    TreasuryOutRecordedMessage recorded = GSON.fromJson(json, TreasuryOutRecordedMessage.class);
                    if (treasuryOutRecordedHandler != null) {
                        treasuryOutRecordedHandler.accept(recorded);
                    } else {
                        SeqClient.LOGGER.warn(
                                "[WebSocket] Received treasury_out_recorded but handler is not registered");
                    }
                }
                case "guild_storage_snapshot" -> {
                    long emeraldCurrent = json.get("emerald_current").getAsLong();
                    long emeraldMax = json.get("emerald_max").getAsLong();
                    long aspectCurrent = json.get("aspect_current").getAsLong();
                    long aspectMax = json.get("aspect_max").getAsLong();
                    SeqClient.LOGGER.info(
                            "[WebSocket] Applying guild_storage_snapshot emerald={}/{} aspect={}/{}",
                            emeraldCurrent,
                            emeraldMax,
                            aspectCurrent,
                            aspectMax);
                    Minecraft.getInstance().execute(() -> GuildStorageTracker.getInstance().applyRemoteSnapshot(
                            emeraldCurrent, emeraldMax, aspectCurrent, aspectMax));
                }
                case "guild_chat" -> {
                    if (!shouldDisplayGuildChat(json)) return;
                    GuildChatMessage msg = parseGuildChatMessage(json);
                    if (msg == null) {
                        SeqClient.LOGGER.warn("[WebSocket] Ignoring malformed displayable guild_chat");
                    } else if (guildChatHandler != null) {
                        guildChatHandler.accept(msg);
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received guild_chat but handler is not registered");
                    }
                }
                case "discord_chat" -> {
                    if (discordChatHandler != null) {
                        String username = json.get("username").getAsString();
                        String msg = json.get("message").getAsString();
                        ConfigManager configManager = SeqClient.getConfigManager();
                        if (configManager != null
                                && shouldIgnoreDiscordChatSender(username, configManager.ignoredBridgeUsers())) {
                            SeqClient.LOGGER.debug("[WebSocket] Ignoring discord_chat from {}", username);
                            return;
                        }
                        // The Discord user id is the only stable handle on a bridge
                        // sender: display names change and collide. It is optional
                        // because older backends do not send it, in which case
                        // matching falls back to the name.
                        String discordId = extractPrimitiveString(json, "discord_id");
                        SeqClient.LOGGER.info(
                                "[WebSocket] Dispatching discord_chat from {} discordId={}",
                                username,
                                discordId != null);
                        discordChatHandler.accept(new DiscordChatMessage(username, msg, discordId));
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received discord_chat but handler is not registered");
                    }
                }
                case "party_finder_update" -> {
                    String action = json.has("action") && !json.get("action").isJsonNull()
                            ? json.get("action").getAsString()
                            : "unknown";
                    if (partyFinderUpdateHandler != null) {
                        JsonObject listingJson = json.getAsJsonObject("listing");
                        SeqClient.LOGGER.info(
                                "[WebSocket] Dispatching party_finder_update action={} hasListing={}",
                                action,
                                listingJson != null);
                        partyFinderUpdateHandler.accept(new PartyFinderUpdateMessage(action, listingJson));
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received party_finder_update but handler is not registered");
                    }
                }
                case "raid_profile_update" -> {
                    String action = extractPrimitiveString(json, "action");
                    JsonObject profileJson = json.has("profile") && json.get("profile").isJsonObject()
                            ? json.getAsJsonObject("profile")
                            : null;
                    SeqClient.LOGGER.info(
                            "[WebSocket] raid_profile_update action={} hasProfile={} handlerPresent={}",
                            action,
                            profileJson != null,
                            raidProfileUpdateHandler != null);
                    if (raidProfileUpdateHandler != null && profileJson != null) {
                        raidProfileUpdateHandler.accept(new RaidProfileUpdateMessage(action, profileJson));
                    }
                }
                case "party_finder_invite" -> {
                    if (partyFinderInviteHandler != null) {
                        long listingId = json.get("listing_id").getAsLong();
                        String inviterUUID = json.get("inviter_uuid").getAsString();
                        String inviteToken = json.get("invite_token").getAsString();

                        JsonObject listingJson = null;
                        if (json.has("listing") && json.get("listing").isJsonObject()) {
                            listingJson = json.getAsJsonObject("listing");
                        }

                        SeqClient.LOGGER.info(
                                "[WebSocket] Dispatching party_finder_invite listingId={} inviterUUID={} tokenPresent={} hasListing={}",
                                listingId,
                                inviterUUID,
                                inviteToken != null && !inviteToken.isBlank(),
                                listingJson != null);

                        partyFinderInviteHandler.accept(
                                new PartyFinderInviteMessage(listingId, inviterUUID, inviteToken, listingJson));
                    } else {
                        SeqClient.LOGGER.warn("[WebSocket] Received party_finder_invite but handler is not registered");
                    }
                }
                case "party_finder_stale_warning" -> {
                    if (partyFinderStaleWarningHandler != null) {
                        String reason = extractPrimitiveString(json, "reason");
                        long listingId = json.get("listing_id").getAsLong();
                        Instant disbandAt = json.has("disband_at") && !json.get("disband_at").isJsonNull()
                                ? Instant.parse(json.get("disband_at").getAsString())
                                : null;
                        long minutesRemaining = json.has("minutes_remaining")
                                ? json.get("minutes_remaining").getAsLong()
                                : 0L;

                        SeqClient.LOGGER.info(
                                "[WebSocket] Dispatching party_finder_stale_warning reason={} listingId={} disbandAt={} minutesRemaining={}",
                                reason,
                                listingId,
                                disbandAt,
                                minutesRemaining);

                        partyFinderStaleWarningHandler.accept(
                                new PartyFinderStaleWarningMessage(reason, listingId, disbandAt, minutesRemaining));
                    } else {
                        SeqClient.LOGGER.warn(
                                "[WebSocket] Received party_finder_stale_warning but handler is not registered");
                    }
                }
                case "error" -> {
                    String error = extractBackendErrorMessage(json);
                    String backendCode = extractBackendErrorCode(json);
                    String requestId = extractPrimitiveString(json, "request_id");
                    String minimumSafeVersion = extractMinimumSafeVersion(json);
                    String capability = extractCapability(json);
                    int status = extractStatusCode(json);
                    String normalized = error.toLowerCase(Locale.ROOT);

                    if (treasuryOnlyConnection
                            && requestId == null
                            && isTreasuryAuthenticationError(backendCode)) {
                        treasurySessionAuthenticator.reset();
                        authFailed = true;
                        autoReconnect = false;
                        notifyConnectionFailure(
                                "Treasury identity verification failed: " + error,
                                true);
                        finishConnectFlow();
                        closeTreasuryAuthFailure();
                        return;
                    }

                    if (requestId != null
                            && treasuryOutErrorHandler != null
                            && treasuryOutErrorHandler.test(
                                    new TreasuryOutErrorMessage(requestId, backendCode, error))) {
                        if ("token_invalid".equalsIgnoreCase(backendCode)) {
                            authFailed = true;
                            authenticated = false;
                            registerAuthFailure();
                            SeqClient.getAuthService()
                                    .invalidateSession(
                                            AuthErrorCode.TOKEN_INVALID,
                                            "Backend rejected the stored token. Re-authentication required.");
                        } else if ("mod_version_unsupported".equalsIgnoreCase(backendCode)) {
                            autoReconnect = false;
                        }
                        return;
                    }

                    if (isSilentGuildChatMembershipReject(backendCode, capability, normalized)) {
                        SeqClient.LOGGER.info("[WebSocket] Guild chat relay rejected because sender is not in guild");
                        return;
                    }

                    if (isSessionMembershipReject(backendCode, capability, normalized)) {
                        disableMemberFeaturesForSession();
                        return;
                    }

                    SeqClient.LOGGER.warn(
                            "[WebSocket] Backend error status={} code={} message={}", status, backendCode, error);

                    if ("mod_version_unsupported".equalsIgnoreCase(backendCode) || status == 426) {
                        if (shouldDisableReconnectForVersionRejection(capability)) {
                            autoReconnect = false;
                        }
                        maybeNotifyVersionRejection(capability, minimumSafeVersion, error);
                        return;
                    }

                    if (isSessionAuthenticationError(status, capability, normalized)) {
                        authFailed = true;
                        authenticated = false;
                        registerAuthFailure();
                        notify("Invalid auth request. Run /seq connect to start a fresh backend session.");
                        return;
                    }

                    if (status == 401 || normalized.contains("invalid token") || normalized.contains("expired")) {
                        authFailed = true;
                        authenticated = false;
                        registerAuthFailure();
                        SeqClient.getAuthService()
                                .invalidateSession(
                                        normalized.contains("expired")
                                                ? AuthErrorCode.TOKEN_EXPIRED
                                                : AuthErrorCode.TOKEN_INVALID,
                                        normalized.contains("expired")
                                                ? "Backend token expired. Re-authentication required."
                                                : "Backend rejected the stored token. Re-authentication required.");
                        notify(SeqClient.getAuthService().getLastError().getMessage());
                        return;
                    }

                    if (isPartyFinderError(json, normalized)) {
                        SeqClient.getPartyFinderManager().pushUiError(error);
                        return;
                    }

                    if (isCapabilityAuthorizationReject(status, capability)) {
                        SeqClient.LOGGER.info(
                                "[WebSocket] Capability authorization rejected capability={} message={}",
                                capability,
                                error);
                        notify(formatCapabilityAccessDenied(capability, error));
                        return;
                    }

                    if (status == 403 || normalized.contains("not in guild") || normalized.contains("guild")) {
                        notInGuild = true;
                        authFailed = true;
                        authenticated = false;
                        notify("Access denied by backend authorization.");
                        SeqClient.LOGGER.warn(
                                "[WebSocket] Guild error detected; disabling auto-reconnect and closing socket");
                        autoReconnect = false;
                        close();
                        return;
                    }

                    if (normalized.contains("validation")) {
                        if ("Unknown backend error".equals(error)) {
                            notify("Request rejected by backend validation. Please check your input.");
                        } else {
                            notify(error);
                        }
                        return;
                    }

                    notify("Error: " + error);
                }
                default -> SeqClient.LOGGER.warn("[WebSocket] Unhandled incoming message type={}", type);
            }
        } catch (Exception e) {
            SeqClient.LOGGER.error("[WebSocket] Failed to handle message payload={}", truncate(message, 512), e);
        }
    }

    // ── Handler registration ──

    public static void onGuildChat(Consumer<GuildChatMessage> handler) {
        guildChatHandler = handler;
    }

    public static void onDiscordChat(Consumer<DiscordChatMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering discord_chat handler present={}", handler != null);
        discordChatHandler = handler;
    }

    public static void onPartyFinderUpdate(Consumer<PartyFinderUpdateMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering party_finder_update handler present={}", handler != null);
        partyFinderUpdateHandler = handler;
    }

    public static void onRaidProfileUpdate(Consumer<RaidProfileUpdateMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering raid_profile_update handler present={}", handler != null);
        raidProfileUpdateHandler = handler;
    }

    public static void onPartyFinderInvite(Consumer<PartyFinderInviteMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering party_finder_invite handler present={}", handler != null);
        partyFinderInviteHandler = handler;
    }

    public static void onPartyFinderStaleWarning(Consumer<PartyFinderStaleWarningMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering party_finder_stale_warning handler present={}", handler != null);
        partyFinderStaleWarningHandler = handler;
    }

    public static void onBombSharePrompt(Consumer<BombSharePromptMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering bomb_share_prompt handler present={}", handler != null);
        bombSharePromptHandler = handler;
    }

    public static void onBombShareResult(Consumer<BombShareResultMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering bomb_share_result handler present={}", handler != null);
        bombShareResultHandler = handler;
    }

    public static void onTreasuryOutRecorded(Consumer<TreasuryOutRecordedMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering treasury_out_recorded handler present={}", handler != null);
        treasuryOutRecordedHandler = handler;
    }

    public static void onTreasuryOutError(Predicate<TreasuryOutErrorMessage> handler) {
        SeqClient.LOGGER.info("[WebSocket] Registering treasury error handler present={}", handler != null);
        treasuryOutErrorHandler = handler;
    }

    public Optional<BombSharePromptMessage> getPendingBombSharePrompt(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pendingBombSharePrompts.get(requestId));
    }

    public Optional<BombSharePromptMessage> removePendingBombSharePrompt(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pendingBombSharePrompts.remove(requestId));
    }

    public boolean hasPendingBombSharePrompt(String requestId) {
        return requestId != null && !requestId.isBlank() && pendingBombSharePrompts.containsKey(requestId);
    }

    private static String truncate(String input, int maxLength) {
        if (input == null) {
            return "null";
        }
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength) + "...";
    }

    // ── Utility ──

    public static boolean isConnected() {
        return instance != null && instance.isOpen() && instance.authenticated;
    }

    public static boolean isLiveWarTelemetryReady() {
        ConnectionManager current = instance;
        return current != null
                && current.isReadyForLiveWarTelemetry()
                && WynncraftServerPolicy.currentScope() == WynncraftServerPolicy.Scope.MAIN;
    }

    public static boolean isTreasuryOutConnected() {
        ConnectionManager current = instance;
        return current != null
                && treasuryConnectionReady(
                        current.isOpen(),
                        current.treasuryOnlyConnection,
                        current.treasurySessionAuthenticator.isAuthenticated());
    }

    static boolean treasuryConnectionReady(boolean open, boolean treasuryOnly, boolean sessionAuthenticated) {
        return open && treasuryOnly && sessionAuthenticated;
    }

    public boolean isDiscordLinked() {
        return hasDiscordUsername(getLinkedDiscordUsername());
    }

    public String getLinkedDiscordUsername() {
        return SeqClient.getConfigManager().getDiscordUsername();
    }

    public void unlinkLocally() {
        pendingGuildWarEvents.clear();
        disconnectInternal(false);
        SeqClient.getAuthService().clearSession();
        clearDiscordUsername();
        notify("Backend session cleared.");
    }

    private boolean canAttemptAuthNow() {
        long now = System.currentTimeMillis();
        if (now >= nextAllowedAuthAttemptAtMs) {
            return true;
        }
        long waitMs = nextAllowedAuthAttemptAtMs - now;
        SeqClient.LOGGER.warn("[WebSocket] Auth attempt throttled waitMs={}", waitMs);
        notifyConnectionFailure("Auth throttled. Retrying in " + Math.max(1, waitMs / 1000) + "s.", false);
        return false;
    }

    private void registerAuthFailure() {
        long delay = Math.min(AUTH_BACKOFF_BASE_MS * (1L << Math.min(authAttempt, 5)), AUTH_BACKOFF_CAP_MS);
        authAttempt++;
        nextAllowedAuthAttemptAtMs = System.currentTimeMillis() + delay;
    }

    private boolean canSendAuthenticated(String type) {
        if (!authenticated || authFailed || notInGuild) {
            SeqClient.LOGGER.warn(
                    "[WebSocket] Dropping {}: authenticated={} authFailed={} notInGuild={}",
                    type,
                    authenticated,
                    authFailed,
                    notInGuild);
            return false;
        }
        if (memberFeaturesDisabled && isSequoiaMemberOnlyType(type)) {
            SeqClient.LOGGER.debug("[WebSocket] Dropping disabled Sequoia-only feature type={}", type);
            return false;
        }
        return true;
    }

    private void disableMemberFeaturesForSession() {
        membershipProbePending = false;
        pendingGuildWarEvents.clear();
        if (memberFeaturesDisabled) {
            SeqClient.LOGGER.debug("[WebSocket] Additional non-member feature rejection suppressed");
            return;
        }

        memberFeaturesDisabled = true;
        SeqClient.LOGGER.info("[WebSocket] Non-member session detected; Sequoia-only features are disabled");
        notify("You are not a Sequoia member. Sequoia-only features are disabled for this session.");
    }

    private boolean canSendThrottleLimited(String type) {
        long now = System.currentTimeMillis();
        if (now < nextPrivilegedSendAtMs) {
            SeqClient.LOGGER.debug("[WebSocket] Throttled {} send", type);
            return false;
        }
        nextPrivilegedSendAtMs = now + PRIVILEGED_SEND_THROTTLE_MS;
        return true;
    }

    static boolean isAuthenticatedOutboundType(String type) {
        return isServerScopedType(type) && !TreasuryOutRequest.TYPE.equals(type);
    }

    static boolean shouldUseTreasuryOnlyConnection(String activeMinecraftUsername) {
        return TreasuryOutManager.isTreasuryMinecraftAccount(activeMinecraftUsername);
    }

    private static boolean isTreasuryAuthenticationError(String code) {
        return "treasury_auth_failed".equalsIgnoreCase(code)
                || "treasury_auth_timeout".equalsIgnoreCase(code);
    }

    private static void joinActiveMinecraftSession(String nonce) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft.getUser();
        if (user == null
                || !TreasuryOutManager.isTreasuryMinecraftAccount(user.getName())
                || user.getProfileId() == null
                || user.getAccessToken() == null
                || user.getAccessToken().isBlank()) {
            throw new IllegalStateException("The active Minecraft session is not eligible for Treasury OUT.");
        }
        minecraft.services()
                .sessionService()
                .joinServer(user.getProfileId(), user.getAccessToken(), nonce);
    }

    private void sendTreasuryAuthResponse(String nonce) {
        if (instance != this || !treasuryOnlyConnection || !isOpen()) {
            treasurySessionAuthenticator.reset();
            return;
        }
        sendPrepared(TreasuryAuthResponse.TYPE, serializeTreasuryAuthResponse(new TreasuryAuthResponse(nonce)));
        SeqClient.LOGGER.debug("[TreasuryAuth] Minecraft session joined; response sent to backend");
    }

    private void handleTreasuryAuthFailure(Throwable failure) {
        authFailed = true;
        autoReconnect = false;
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Minecraft session verification failed."
                : failure.getMessage();
        SeqClient.LOGGER.warn("[TreasuryAuth] Minecraft session proof failed: {}", message);
        notifyConnectionFailure("Treasury identity verification failed. Restart Minecraft and try again.", true);
        finishConnectFlow();
        closeTreasuryAuthFailure();
    }

    private void closeTreasuryAuthFailure() {
        if (isOpen()) {
            close(1008, "Treasury authentication failed");
        }
    }

    private static String currentMinecraftUsername() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getUser() == null ? null : minecraft.getUser().getName();
    }

    static boolean isSequoiaMemberOnlyType(String type) {
        return "bomb_share_request".equals(type)
                || "bomb_share_submit".equals(type)
                || "guild_chat".equals(type)
                || "guild_membership_event".equals(type)
                || "guild_rank_event".equals(type)
                || "guild_alliance_update".equals(type)
                || "guild_alliance_snapshot".equals(type)
                || "guild_raid_announcement".equals(type)
                || "guild_bank_event".equals(type)
                || "guild_storage_snapshot".equals(type)
                || "guild_storage_reward".equals(type)
                || "guild_war_queue".equals(type)
                || "guild_war_queue_cancel".equals(type)
                || "guild_war_submission".equals(type)
                || "war_status".equals(type)
                || "war_tower_update".equals(type)
                || "get_connected".equals(type);
    }

    static boolean isServerScopedType(String type) {
        return "bomb_share_request".equals(type)
                || "bomb_share_submit".equals(type)
                || "treasury_out".equals(type)
                || "guild_chat".equals(type)
                || "guild_membership_event".equals(type)
                || "guild_rank_event".equals(type)
                || "guild_alliance_update".equals(type)
                || "guild_alliance_snapshot".equals(type)
                || "guild_raid_announcement".equals(type)
                || "guild_bank_event".equals(type)
                || "guild_storage_snapshot".equals(type)
                || "guild_storage_reward".equals(type)
                || "guild_war_queue".equals(type)
                || "guild_war_queue_cancel".equals(type)
                || "guild_war_submission".equals(type)
                || "war_status".equals(type)
                || "war_tower_update".equals(type)
                || "party_class_update".equals(type)
                || "party_sync_snapshot".equals(type)
                || "party_sync_member_removed".equals(type)
                || "get_connected".equals(type);
    }

    static boolean isThrottleLimitedType(String type) {
        return "bomb_share_request".equals(type)
                || "bomb_share_submit".equals(type)
                || "treasury_out".equals(type)
                || "guild_chat".equals(type)
                || "guild_membership_event".equals(type)
                || "guild_rank_event".equals(type)
                || "guild_alliance_update".equals(type)
                || "guild_raid_announcement".equals(type)
                || "guild_bank_event".equals(type)
                || "guild_war_queue".equals(type)
                || "guild_war_queue_cancel".equals(type)
                || "guild_war_submission".equals(type)
                || "party_class_update".equals(type)
                || "party_sync_snapshot".equals(type)
                || "party_sync_member_removed".equals(type)
                || "get_connected".equals(type);
    }

    private static String sanitizeAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        String trimmed = avatarUrl.trim();
        if (!URL_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return trimmed;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sanitizeMinecraftUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String trimmed = username.trim();
        if (!MC_USERNAME_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private static String sanitizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        String trimmed = nickname.trim();
        if (trimmed.length() > 64) {
            return null;
        }
        return trimmed;
    }

    private static int extractStatusCode(JsonObject json) {
        if (json == null) {
            return -1;
        }
        if (json.has("status") && json.get("status").isJsonPrimitive()) {
            try {
                return json.get("status").getAsInt();
            } catch (Exception ignored) {
            }
        }
        if (json.has("code") && json.get("code").isJsonPrimitive()) {
            try {
                return json.get("code").getAsInt();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static String extractBackendErrorCode(JsonObject json) {
        return extractPrimitiveString(json, "code");
    }

    private static String extractBackendErrorMessage(JsonObject json) {
        if (json == null) {
            return "Unknown backend error";
        }
        String message = extractPrimitiveString(json, "message");
        if (message != null) {
            return message;
        }
        message = extractPrimitiveString(json, "error");
        if (message != null) {
            return message;
        }
        message = extractPrimitiveString(json, "detail");
        if (message != null) {
            return message;
        }
        return "Unknown backend error";
    }

    private static String extractMinimumSafeVersion(JsonObject json) {
        return extractPrimitiveString(json, "minimum_safe_version");
    }

    private static String extractCapability(JsonObject json) {
        return extractPrimitiveString(json, "capability");
    }

    static boolean isSilentGuildChatMembershipReject(String backendCode, String capability, String normalizedMessage) {
        return "not_in_guild".equalsIgnoreCase(backendCode)
                && "guild_chat".equalsIgnoreCase(capability)
                && normalizedMessage != null
                && normalizedMessage.contains("guild");
    }

    static boolean isSessionMembershipReject(String backendCode, String capability, String normalizedMessage) {
        return "not_in_guild".equalsIgnoreCase(backendCode)
                && (capability == null || capability.isBlank())
                && normalizedMessage != null
                && normalizedMessage.contains("sequoia")
                && normalizedMessage.contains("member");
    }

    static boolean isSessionAuthenticationError(int status, String capability, String normalizedMessage) {
        if (capability != null && !capability.isBlank()) {
            return false;
        }
        return status == 400
                || (normalizedMessage != null && normalizedMessage.contains("invalid auth request"));
    }

    static boolean shouldDisableReconnectForVersionRejection(String capability) {
        return capability == null || capability.isBlank();
    }

    private static boolean isCapabilityAuthorizationReject(int status, String capability) {
        return status == 403 && capability != null && !capability.isBlank();
    }

    private static String formatCapabilityAccessDenied(String capability, String backendMessage) {
        String feature = capability.replace('_', ' ');
        if (backendMessage != null && !backendMessage.isBlank() && !"Unknown backend error".equals(backendMessage)) {
            return feature + " unavailable: " + backendMessage;
        }
        return feature + " unavailable for this account.";
    }

    private void maybeNotifyVersionRejection(String capability, String minimumSafeVersion, String backendMessage) {
        String scope = capability == null || capability.isBlank() ? "general" : capability;
        int count = versionRejectionCounts.merge(scope, 1, Integer::sum);
        int interval = VERSION_REMINDER_INTERVALS.getOrDefault(scope, Integer.MAX_VALUE);
        if (count != 1 && (interval == Integer.MAX_VALUE || count % interval != 0)) {
            return;
        }

        if (count == 1) {
            if (backendMessage != null && !backendMessage.isBlank() && !"Unknown backend error".equals(backendMessage)) {
                notify(backendMessage);
                return;
            }
            String targetVersion = minimumSafeVersion != null && !minimumSafeVersion.isBlank()
                    ? minimumSafeVersion
                    : "the required version";
            notify("Update Sequoia to at least " + targetVersion + ".");
            return;
        }

        String feature = switch (scope) {
            case "bomb_share_request", "bomb_share_submit" -> "bomb share relays";
            case "guild_chat" -> "guild chat relays";
            case "guild_raid_announcement" -> "raid completion relays";
            case "guild_bank_event" -> "guild bank relays";
            case "guild_war_queue", "guild_war_queue_cancel", "guild_war_submission" -> "guild war tracking";
            default -> "some Sequoia features";
        };
        String targetVersion = minimumSafeVersion != null && !minimumSafeVersion.isBlank()
                ? minimumSafeVersion
                : "a newer version";
        notify("Sequoia is outdated. Some " + feature + " may not work until you update to " + targetVersion + ".");
    }

    private static String extractPrimitiveString(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            String value = json.get(key).getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<BombShareType> parseBombShareTypes(JsonArray requestedTypesJson) {
        if (requestedTypesJson == null) {
            return List.of();
        }

        List<BombShareType> requestedTypes = new ArrayList<>();
        requestedTypesJson.forEach(element -> BombShareType.fromWireValue(element.getAsString()).ifPresent(type -> {
            if (!requestedTypes.contains(type)) {
                requestedTypes.add(type);
            }
        }));
        return List.copyOf(requestedTypes);
    }

    private static List<String> parsePrimitiveStringArray(JsonArray jsonArray) {
        if (jsonArray == null) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        jsonArray.forEach(element -> {
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        });
        return List.copyOf(values);
    }

    private static boolean isPartyFinderError(JsonObject json, String normalizedMessage) {
        if (normalizedMessage != null
                && (normalizedMessage.contains("party finder")
                        || normalizedMessage.contains("party_finder")
                        || normalizedMessage.contains("listing")
                        || normalizedMessage.contains("invite"))) {
            return true;
        }
        if (json == null) {
            return false;
        }
        if (json.has("context") && json.get("context").isJsonPrimitive()) {
            String context = json.get("context").getAsString().toLowerCase(Locale.ROOT);
            if (context.contains("party_finder") || context.contains("party finder")) {
                return true;
            }
        }
        if (json.has("request_type") && json.get("request_type").isJsonPrimitive()) {
            String requestType = json.get("request_type").getAsString().toLowerCase(Locale.ROOT);
            return requestType.startsWith("party_")
                    || requestType.contains("listing")
                    || requestType.contains("invite");
        }
        return false;
    }

    public String getEnvironment() {
        return BuildConfig.ENVIRONMENT;
    }

    public String getUptimeString() {
        if (connectedSince == null) return null;
        java.time.Duration dur = java.time.Duration.between(connectedSince, Instant.now());
        long hours = dur.toHours();
        long mins = dur.toMinutesPart();
        long secs = dur.toSecondsPart();
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }

    static boolean hasDiscordUsername(String discordUsername) {
        return discordUsername != null && !discordUsername.isBlank();
    }

    static boolean shouldIgnoreDiscordChatSender(String username, Collection<String> ignoredBridgeUsers) {
        if (username == null || ignoredBridgeUsers == null) {
            return false;
        }
        String loweredUsername = username.toLowerCase(Locale.ROOT);
        for (String ignoredBridgeUser : ignoredBridgeUsers) {
            if (ignoredBridgeUser != null && !ignoredBridgeUser.isBlank() && loweredUsername.contains(ignoredBridgeUser)) {
                return true;
            }
        }
        return false;
    }

    private void storeDiscordUsername(String discordUsername) {
        if (hasDiscordUsername(discordUsername)) {
            SeqClient.getConfigManager().setDiscordUsername(discordUsername);
        }
    }

    private void clearDiscordUsername() {
        SeqClient.getConfigManager().clearDiscordUsername();
    }

    // ── Message records ──

    public record GuildChatMessage(String username, String nickname, String message) {}

    /**
     * A message relayed from the Discord guild bridge.
     *
     * @param discordId the sender's Discord user id, or {@code null} when the backend
     *                  does not supply one. Prefer it over {@code username} for any
     *                  identity matching: it is stable, whereas a display name can be
     *                  changed or shared.
     */
    public record DiscordChatMessage(String username, String message, String discordId) {

        public DiscordChatMessage(String username, String message) {
            this(username, message, null);
        }
    }

    public record PartyFinderUpdateMessage(String action, JsonObject listingJson) {}

    /**
     * One member's raid profile changing.
     * <p>
     * {@code action} is {@code updated} or {@code removed}. On a removal the
     * payload is still shaped like a profile, with its fields set to null rather
     * than omitted, so only {@code minecraft} is meaningful there.
     */
    public record RaidProfileUpdateMessage(String action, JsonObject profileJson) {

        public boolean isRemoval() {
            return "removed".equalsIgnoreCase(action);
        }
    }

    public record PartyFinderInviteMessage(
            long listingId, String inviterUUID, String inviteToken, JsonObject listingJson) {}

    public record PartyFinderStaleWarningMessage(
            String reason, long listingId, Instant disbandAt, long minutesRemaining) {}

    public record BombSharePromptMessage(
            String requestId,
            String requesterUsername,
            String canonicalKey,
            List<BombShareType> requestedTypes,
            Instant expiresAt,
            boolean firstPrompt) {}

    public record BombShareResultMessage(
            String requestId,
            String canonicalKey,
            List<BombShareType> requestedTypes,
            List<String> worlds,
            int shareCount) {}
}
