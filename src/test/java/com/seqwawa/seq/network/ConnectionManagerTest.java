package com.seqwawa.seq.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.seqwawa.seq.model.ChatItemPreview;
import com.seqwawa.seq.model.GuildWarSubmission;
import com.seqwawa.seq.model.WarStatusUpdate;
import com.seqwawa.seq.model.WarTowerUpdate;
import com.seqwawa.seq.model.WynnClassType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConnectionManagerTest {

    @Test
    void delayedGuildChatIsDroppedWhenItsSessionExpiresBeforeDelivery() {
        var received = new java.util.ArrayList<ConnectionManager.GuildChatMessage>();
        var currentSession = new AtomicBoolean(true);
        var payload = JsonParser.parseString(
                "{\"username\":\"Sender\",\"message\":\"hello\",\"should_be_displayed\":true}").getAsJsonObject();
        ConnectionManager.onGuildChat(received::add);
        try {
            Runnable delivery = ConnectionManager.guildChatDelivery(payload, currentSession::get, () -> {
                throw new AssertionError("Expired sessions must be rejected before reading queue state");
            });
            // Simulate a disconnect, socket replacement, or game/account change while waiting on the client thread.
            currentSession.set(false);
            delivery.run();
            assertTrue(received.isEmpty());
        } finally {
            ConnectionManager.onGuildChat(null);
        }
    }

    @Test
    void delayedGuildChatUsesQueueStateAtDeliveryForTheCurrentSession() {
        var received = new java.util.ArrayList<ConnectionManager.GuildChatMessage>();
        var inQueue = new AtomicBoolean(false);
        var payload = JsonParser.parseString(
                "{\"username\":\"Sender\",\"message\":\"hello\",\"should_be_displayed\":false}").getAsJsonObject();
        ConnectionManager.onGuildChat(received::add);
        try {
            Runnable delivery = ConnectionManager.guildChatDelivery(payload, () -> true, inQueue::get);
            assertTrue(received.isEmpty());
            inQueue.set(true);
            delivery.run();
            assertEquals(List.of(new ConnectionManager.GuildChatMessage("Sender", null, "hello")), received);
        } finally {
            ConnectionManager.onGuildChat(null);
        }
    }

    private static void dispatchGuildChat(String payload, boolean inQueue) {
        ConnectionManager.dispatchGuildChat(JsonParser.parseString(payload).getAsJsonObject(), inQueue);
    }

    @Test
    void queuePayloadRequestsDisplayAndRetainsExistingValidation() {
        var payload = ConnectionManager.buildGuildChatPayload(
                "Sender", null, " hello ", "https://mc-heads.net/avatar/Sender/128", List.of(), true);
        assertTrue(payload.getAsJsonPrimitive("should_be_displayed").isBoolean());
        assertTrue(payload.get("should_be_displayed").getAsBoolean());
        assertEquals("Sender", payload.get("username").getAsString());
        assertEquals("hello", payload.get("message").getAsString());
        assertEquals("https://mc-heads.net/avatar/Sender/128", payload.get("avatar_url").getAsString());
        assertFalse(payload.has("nickname"));
        assertFalse(payload.has("item_previews"));
        assertEquals(400, ConnectionManager.buildGuildChatPayload(
                "Sender", null, "x".repeat(400), null, List.of(), true).get("message").getAsString().length());
        assertThrows(IllegalArgumentException.class, () -> ConnectionManager.buildGuildChatPayload(
                "Sender", null, "x".repeat(401), null, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> ConnectionManager.buildGuildChatPayload(
                "Sender", null, " ", null, List.of(), true));
    }

    @Test
    void queueDispatchIgnoresDisplayFlagButStillRequiresValidMessageFields() {
        var received = new java.util.ArrayList<ConnectionManager.GuildChatMessage>();
        ConnectionManager.onGuildChat(received::add);
        try {
            for (String flag : List.of("true", "false", "null", "\"true\"", "1", "{}", "[]")) {
                String payload = "{\"username\":\"Sender\",\"message\":\"hello\",\"should_be_displayed\":" + flag + "}";
                received.clear();
                dispatchGuildChat(payload, true);
                assertEquals(List.of(new ConnectionManager.GuildChatMessage("Sender", null, "hello")), received, flag);
                received.clear();
                dispatchGuildChat(payload, false);
                assertEquals("true".equals(flag) ? 1 : 0, received.size(), flag);
            }
            received.clear();
            dispatchGuildChat("{\"username\":\"Sender\",\"message\":\"hello\"}", false);
            assertTrue(received.isEmpty());
            dispatchGuildChat("{\"username\":\"Sender\",\"message\":\"hello\"}", true);
            assertEquals(1, received.size());
            received.clear();
            for (String payload : List.of("{}", "{\"username\":true,\"message\":\"hello\"}",
                    "{\"username\":\"Sender\",\"message\":null}", "{\"username\":\"Sender\",\"message\":1}")) {
                dispatchGuildChat(payload, true);
            }
            assertTrue(received.isEmpty());
        } finally {
            ConnectionManager.onGuildChat(null);
        }
    }

    @Test
    void outgoingGuildChatRequestsNoDisplayAndPreservesFields() {
        var payload = ConnectionManager.buildGuildChatPayload("Sender", "Nickname", "hello", "https://example.com", List.of());
        assertTrue(payload.getAsJsonPrimitive("should_be_displayed").isBoolean());
        assertFalse(payload.get("should_be_displayed").getAsBoolean());
        assertEquals("Sender", payload.get("username").getAsString());
        assertEquals("Nickname", payload.get("nickname").getAsString());
        assertEquals("hello", payload.get("message").getAsString());
        assertEquals("https://example.com", payload.get("avatar_url").getAsString());
    }

    @Test
    void guildChatOnlyDispatchesExplicitTrueAndValidStrings() {
        var received = new java.util.ArrayList<ConnectionManager.GuildChatMessage>();
        ConnectionManager.onGuildChat(received::add);
        try {
            for (String flag : List.of("false", "null", "\"true\"", "1", "{}", "[]")) {
                dispatchGuildChat("{\"type\":\"guild_chat\",\"should_be_displayed\":" + flag + "}", false);
            }
            dispatchGuildChat("{\"type\":\"guild_chat\"}", false);
            for (String fields : List.of("", ",\"username\":true,\"message\":\"hello\"", ",\"username\":\"Sender\",\"message\":null")) {
                dispatchGuildChat("{\"type\":\"guild_chat\",\"should_be_displayed\":true" + fields + "}", false);
            }
            assertTrue(received.isEmpty());
            String echo = "{\"type\":\"guild_chat\",\"should_be_displayed\":true,\"username\":\"Sender\",\"message\":\"hello\"}";
            dispatchGuildChat(echo, false);
            dispatchGuildChat(echo, false);
            dispatchGuildChat("{\"type\":\"guild_chat\",\"should_be_displayed\":true,\"username\":\"Other\",\"nickname\":\"Nick\",\"message\":\"hi\"}", false);
            assertEquals(List.of(new ConnectionManager.GuildChatMessage("Sender", null, "hello"),
                    new ConnectionManager.GuildChatMessage("Sender", null, "hello"),
                    new ConnectionManager.GuildChatMessage("Other", "Nick", "hi")), received);
        } finally {
            ConnectionManager.onGuildChat(null);
        }
    }

    @Test
    void itemPreviewPayloadIncludesV3Sections() {
        var sections = List.of(
                new ChatItemPreview.Section("Mount Stats", List.of("Speed: 12/20", "Jump Height: 4/8")),
                new ChatItemPreview.Section("Major ID: Hawkeye", List.of("Arrow Storm fires five arrows.")));
        var preview = new ChatItemPreview(
                "Gale's Force",
                "Mythic • Bow • Lv. 95",
                0xAA00AA,
                List.of("3 rerolls"),
                List.of("+26% Walk Speed"),
                List.of(),
                null,
                sections);

        var payload = ConnectionManager.itemPreviewArray(List.of(preview)).get(0).getAsJsonObject();

        assertEquals(2, payload.getAsJsonArray("sections").size());
        assertEquals("Mount Stats", payload.getAsJsonArray("sections").get(0).getAsJsonObject()
                .get("title")
                .getAsString());
        assertEquals("Jump Height: 4/8", payload.getAsJsonArray("sections")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("lines")
                .get(1)
                .getAsString());
    }

    @Test
    void raidAnnouncementIncludesObservedPartyGambitCounts() {
        var payload = ConnectionManager.buildRaidAnnouncementPayload(
                List.of("Reporter", "PartyMember"),
                "The Nameless Anomaly",
                2,
                2048,
                10.367,
                220,
                Map.of("Reporter", 0, "PartyMember", 3));

        assertEquals(0, payload.getAsJsonObject("gambit_counts").get("Reporter").getAsInt());
        assertEquals(3, payload.getAsJsonObject("gambit_counts").get("PartyMember").getAsInt());
        assertFalse(payload.has("gambit_count"));
    }

    @Test
    void raidAnnouncementOmitsUnavailablePartyGambitCounts() {
        var payload = ConnectionManager.buildRaidAnnouncementPayload(
                List.of("Reporter"), "The Nameless Anomaly", 2, 2048, 10.367, 220, Map.of());

        assertFalse(payload.has("gambit_counts"));
    }

    @Test
    void partySyncSnapshotPayloadIsBoundToListing() {
        var payload = ConnectionManager.buildPartySyncSnapshotPayload(
                1204L, true, "Leader", List.of("Leader", "Guest"));

        assertEquals(1204L, payload.get("listing_id").getAsLong());
        assertTrue(payload.get("active").getAsBoolean());
        assertEquals("Leader", payload.get("leader_username").getAsString());
        assertEquals(2, payload.getAsJsonArray("member_usernames").size());
    }

    @Test
    void guildMembershipEventPayloadIdentifiesActionActorAndTarget() {
        var payload = ConnectionManager.buildGuildMembershipEventPayload("uninvited", "GaztheCat", "NewMember");

        assertEquals("uninvited", payload.get("action").getAsString());
        assertEquals("GaztheCat", payload.get("actor").getAsString());
        assertEquals("NewMember", payload.get("target").getAsString());
    }

    @Test
    void websocketHandshakeUsesBearerTokenHeader() {
        Map<String, String> headers = ConnectionManager.buildHandshakeHeaders("abc123", "0.1.3");

        assertEquals(2, headers.size());
        assertEquals("Bearer abc123", headers.get("Authorization"));
        assertEquals("0.1.3", headers.get(ClientVersion.MOD_VERSION_HEADER));
    }

    @Test
    void blankTokenProducesNoHandshakeHeaders() {
        Map<String, String> headers = ConnectionManager.buildHandshakeHeaders("   ", "0.1.3");

        assertEquals(1, headers.size());
        assertEquals("0.1.3", headers.get(ClientVersion.MOD_VERSION_HEADER));
    }

    @Test
    void supersededAuthenticationCannotStartAWebsocket() {
        assertTrue(ConnectionManager.shouldContinueAuthenticatedConnection(4, 4, true, true));
        assertFalse(ConnectionManager.shouldContinueAuthenticatedConnection(4, 5, true, true));
        assertFalse(ConnectionManager.shouldContinueAuthenticatedConnection(4, 4, false, true));
        assertFalse(ConnectionManager.shouldContinueAuthenticatedConnection(4, 4, true, false));
    }

    @Test
    void treasuryOnlyConnectionIsSelectedOnlyForCinfrascitizen() {
        assertTrue(ConnectionManager.shouldUseTreasuryOnlyConnection("cinfrascitizen"));
        assertTrue(ConnectionManager.shouldUseTreasuryOnlyConnection("CinfrasCitizen"));
        assertFalse(ConnectionManager.shouldUseTreasuryOnlyConnection("reyzhia"));
        assertFalse(ConnectionManager.shouldUseTreasuryOnlyConnection(null));
    }

    @Test
    void treasuryConnectionIsReadyOnlyAfterMinecraftSessionProof() {
        assertFalse(ConnectionManager.treasuryConnectionReady(true, true, false));
        assertFalse(ConnectionManager.treasuryConnectionReady(true, false, true));
        assertFalse(ConnectionManager.treasuryConnectionReady(false, true, true));
        assertTrue(ConnectionManager.treasuryConnectionReady(true, true, true));
    }

    @Test
    void guildWarSubmissionPayloadUsesExpectedNestedShape() {
        GuildWarSubmission submission = new GuildWarSubmission(
                "Detlas Suburbs",
                "550e8400-e29b-41d4-a716-446655440000",
                "2026-03-28T01:00:00Z",
                "2026-03-28T00:55:00Z",
                java.util.List.of("Alpha", "Bravo"),
                new GuildWarSubmission.TowerStats(1200, 1800, 2.5, 450000, 0.35),
                410,
                "2026-03-28T00:58:00Z");

        var payload = ConnectionManager.buildGuildWarSubmissionPayload(submission);

        assertEquals("Detlas Suburbs", payload.get("territory").getAsString());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", payload.get("submitted_by").getAsString());
        assertEquals("2026-03-28T01:00:00Z", payload.get("submitted_at").getAsString());
        assertEquals("2026-03-28T00:55:00Z", payload.get("start_time").getAsString());
        assertEquals("Alpha", payload.getAsJsonArray("warrers").get(0).getAsString());
        assertEquals(1200L, payload.getAsJsonObject("results")
                .getAsJsonObject("stats")
                .getAsJsonObject("damage")
                .get("low")
                .getAsLong());
        assertEquals(0.35, payload.getAsJsonObject("results").getAsJsonObject("stats").get("defence").getAsDouble());
        assertEquals(410, payload.get("sr").getAsInt());
        assertEquals("2026-03-28T00:58:00Z", payload.get("completed_at").getAsString());
    }

    @Test
    void liveWarStatusPayloadUsesStatusBecauseTypeIsTheWebsocketDiscriminator() {
        var world = ConnectionManager.buildWarStatusPayload(
                WarStatusUpdate.world(WynnClassType.MAGE, -1517, -5130));
        assertEquals("WORLD", world.get("status").getAsString());
        assertEquals("MAGE", world.get("class").getAsString());
        assertEquals(-1517, world.get("x").getAsInt());
        assertEquals(-5130, world.get("z").getAsInt());
        assertFalse(world.has("territory"));
        assertFalse(world.has("type"));

        var war = ConnectionManager.buildWarStatusPayload(
                WarStatusUpdate.war(WynnClassType.WARRIOR, "Entrance to Olux"));
        assertEquals("WAR", war.get("status").getAsString());
        assertEquals("Entrance to Olux", war.get("territory").getAsString());
        assertFalse(war.has("x"));
        assertFalse(war.has("z"));

        var remove = ConnectionManager.buildWarStatusPayload(WarStatusUpdate.remove());
        assertEquals("REMOVE", remove.get("status").getAsString());
        assertFalse(remove.has("class"));
        assertFalse(remove.has("territory"));
        assertFalse(remove.has("x"));
        assertFalse(remove.has("z"));
    }

    @Test
    void liveWarTowerPayloadPreservesLongMetrics() {
        var payload = ConnectionManager.buildWarTowerUpdatePayload(
                new WarTowerUpdate("Entrance to Olux", 0.8731f, 24_135_275L, 32_143L));

        assertEquals("Entrance to Olux", payload.get("territory").getAsString());
        assertEquals(0.8731f, payload.get("health").getAsFloat());
        assertEquals(24_135_275L, payload.get("ehp").getAsLong());
        assertEquals(32_143L, payload.get("dps").getAsLong());
    }

    @Test
    void liveWarTowerModelRejectsInvalidMetrics() {
        assertThrows(IllegalArgumentException.class, () -> new WarTowerUpdate("Olux", Float.NaN, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new WarTowerUpdate("Olux", -0.1f, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new WarTowerUpdate("Olux", 1.1f, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new WarTowerUpdate("Olux", 0.5f, -1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new WarTowerUpdate("Olux", 0.5f, 1L, -1L));
    }

    @Test
    void liveWarTelemetryTreatsSocketCloseRaceAsFailedSend() {
        assertFalse(ConnectionManager.tryLiveTelemetrySend(() -> {
            throw new IllegalStateException("socket closed");
        }));
        assertTrue(ConnectionManager.tryLiveTelemetrySend(() -> true));
    }

    @Test
    void discordUsernamePresenceTreatsBlankValuesAsUnlinked() {
        assertTrue(ConnectionManager.hasDiscordUsername("SequoiaUser"));
        assertEquals(false, ConnectionManager.hasDiscordUsername(""));
        assertEquals(false, ConnectionManager.hasDiscordUsername("   "));
    }

    @Test
    void discordChatIgnoreMatchesUsernameContainingIgnoredMinecraftName() {
        assertTrue(ConnectionManager.shouldIgnoreDiscordChatSender("SomeUser", List.of("someuser")));
        assertTrue(ConnectionManager.shouldIgnoreDiscordChatSender("[VIP] SomeUser", List.of("someuser")));
        assertTrue(ConnectionManager.shouldIgnoreDiscordChatSender("Guild | someuser", List.of("someuser")));
        assertFalse(ConnectionManager.shouldIgnoreDiscordChatSender("OtherUser", List.of("someuser")));
        assertFalse(ConnectionManager.shouldIgnoreDiscordChatSender(null, List.of("someuser")));
    }

    @Test
    void localCleanCloseDoesNotReconnect() {
        assertFalse(ConnectionManager.shouldReconnectAfterClose(1000, false));
    }

    @Test
    void remoteCloseStillReconnects() {
        assertTrue(ConnectionManager.shouldReconnectAfterClose(1000, true));
    }

    @Test
    void resetForTestClearsReconnectState() {
        ConnectionManager.resetForTest();

        assertFalse(ConnectionManager.hasReconnectTask());
        assertFalse(ConnectionManager.isConnected());
    }

    @Test
    void automaticConnectStaysSuppressedAfterManualDisconnect() {
        assertFalse(ConnectionManager.shouldAttemptAutomaticConnect(false, false, false, false, true));
    }

    @Test
    void automaticConnectSkipsWhileReconnectAlreadyScheduled() {
        assertFalse(ConnectionManager.shouldAttemptAutomaticConnect(false, false, false, true, false));
    }

    @Test
    void automaticConnectRunsOnlyWhenSocketIsIdle() {
        assertTrue(ConnectionManager.shouldAttemptAutomaticConnect(false, false, false, false, false));
        assertFalse(ConnectionManager.shouldAttemptAutomaticConnect(true, false, false, false, false));
        assertFalse(ConnectionManager.shouldAttemptAutomaticConnect(false, true, false, false, false));
        assertFalse(ConnectionManager.shouldAttemptAutomaticConnect(false, false, true, false, false));
    }

    @Test
    void guildStorageMessagesAreServerScopedAuthenticatedOutbound() {
        assertTrue(ConnectionManager.isServerScopedType("guild_storage_snapshot"));
        assertTrue(ConnectionManager.isServerScopedType("guild_storage_reward"));
        assertTrue(ConnectionManager.isServerScopedType("guild_war_queue"));
        assertTrue(ConnectionManager.isServerScopedType("guild_war_queue_cancel"));
        assertTrue(ConnectionManager.isServerScopedType("guild_alliance_update"));
        assertTrue(ConnectionManager.isServerScopedType("guild_alliance_snapshot"));
        assertTrue(ConnectionManager.isServerScopedType("guild_membership_event"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_storage_snapshot"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_storage_reward"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_alliance_update"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_alliance_snapshot"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_membership_event"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("war_status"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("war_tower_update"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("guild_war_queue_cancel"));
    }

    @Test
    void guildStorageMessagesDoNotUseGenericPrivilegedThrottle() {
        assertFalse(ConnectionManager.isThrottleLimitedType("guild_storage_snapshot"));
        assertFalse(ConnectionManager.isThrottleLimitedType("guild_storage_reward"));
        assertTrue(ConnectionManager.isThrottleLimitedType("guild_chat"));
        assertFalse(ConnectionManager.isThrottleLimitedType("war_status"));
        assertFalse(ConnectionManager.isThrottleLimitedType("war_tower_update"));
    }

    @Test
    void scopedGuildChatMembershipRejectsAreSilent() {
        assertTrue(ConnectionManager.isSilentGuildChatMembershipReject(
                "not_in_guild", "guild_chat", "guild chat sender is not in sequoia"));
    }

    @Test
    void unscopedGuildMembershipRejectIdentifiesNonMemberSession() {
        assertTrue(ConnectionManager.isSessionMembershipReject(
                "not_in_guild", null, "this feature is limited to sequoia members"));
        assertTrue(ConnectionManager.isSessionMembershipReject(
                "not_in_guild", "", "you must be a member of sequoia guild to use this feature"));
    }

    @Test
    void scopedMembershipRejectDoesNotDisableWholeSession() {
        assertFalse(ConnectionManager.isSessionMembershipReject(
                "not_in_guild", "guild_chat", "guild chat sender is not in sequoia"));
        assertFalse(ConnectionManager.isSilentGuildChatMembershipReject(
                "not_in_guild", "guild_bank_event", "this feature is limited to sequoia members"));
    }

    @Test
    void capabilityValidationErrorDoesNotInvalidateAuthenticatedSession() {
        assertFalse(ConnectionManager.isSessionAuthenticationError(
                400, "war_status", "invalid war status payload"));
        assertFalse(ConnectionManager.isSessionAuthenticationError(
                400, "war_tower_update", "invalid war tower update payload"));

        assertTrue(ConnectionManager.isSessionAuthenticationError(400, null, "invalid message format"));
        assertTrue(ConnectionManager.isSessionAuthenticationError(
                0, null, "invalid auth request"));
    }

    @Test
    void capabilityVersionRejectionDoesNotDisableGlobalReconnect() {
        assertFalse(ConnectionManager.shouldDisableReconnectForVersionRejection("war_status"));
        assertFalse(ConnectionManager.shouldDisableReconnectForVersionRejection("war_tower_update"));

        assertTrue(ConnectionManager.shouldDisableReconnectForVersionRejection(null));
        assertTrue(ConnectionManager.shouldDisableReconnectForVersionRejection("   "));
    }

    @Test
    void memberOnlyOutboundTypesExcludeUnrestrictedPartyFinderUpdates() {
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_chat"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_alliance_snapshot"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_storage_snapshot"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_war_queue"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_war_queue_cancel"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("guild_war_submission"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("war_status"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("war_tower_update"));
        assertTrue(ConnectionManager.isSequoiaMemberOnlyType("get_connected"));
        assertFalse(ConnectionManager.isSequoiaMemberOnlyType("party_class_update"));
        assertFalse(ConnectionManager.isSequoiaMemberOnlyType("party_sync_snapshot"));
        assertFalse(ConnectionManager.isSequoiaMemberOnlyType("party_sync_member_removed"));
    }

    @Test
    void bombShareMessagesAreScopedAuthenticatedAndThrottleLimited() {
        assertTrue(ConnectionManager.isServerScopedType("bomb_share_request"));
        assertTrue(ConnectionManager.isServerScopedType("bomb_share_submit"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("bomb_share_request"));
        assertTrue(ConnectionManager.isAuthenticatedOutboundType("bomb_share_submit"));
        assertTrue(ConnectionManager.isThrottleLimitedType("bomb_share_request"));
        assertTrue(ConnectionManager.isThrottleLimitedType("bomb_share_submit"));
        assertTrue(ConnectionManager.isThrottleLimitedType("guild_war_queue"));
        assertTrue(ConnectionManager.isThrottleLimitedType("guild_war_queue_cancel"));
        assertFalse(ConnectionManager.isThrottleLimitedType("guild_alliance_snapshot"));
    }

    @Test
    void treasuryOutUsesTypedGsonSerializationWithoutClaimedIdentity() {
        TreasuryOutRequest request = new TreasuryOutRequest(
                "550e8400-e29b-41d4-a716-446655440000",
                "2stx5le+1stx5le+4stx4le",
                "cinfrascitizen",
                "guild event prizes");

        var json = ConnectionManager.serializeTreasuryOutRequest(request);

        assertEquals("treasury_out", json.get("type").getAsString());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.get("request_id").getAsString());
        assertEquals("2stx5le+1stx5le+4stx4le", json.get("amount").getAsString());
        assertEquals("cinfrascitizen", json.get("payouter").getAsString());
        assertEquals("guild event prizes", json.get("reason").getAsString());
        assertFalse(json.has("username"));
        assertFalse(json.has("minecraft_username"));
        assertFalse(json.has("uuid"));
        assertFalse(json.has("minecraft_uuid"));
    }

    @Test
    void treasuryAuthResponseContainsOnlyTypeAndBackendNonce() {
        var json = ConnectionManager.serializeTreasuryAuthResponse(
                new TreasuryAuthResponse("7505801b-9e89-4ef8-a32e-8d55e2f4d011"));

        assertEquals("treasury_auth_response", json.get("type").getAsString());
        assertEquals("7505801b-9e89-4ef8-a32e-8d55e2f4d011", json.get("nonce").getAsString());
        assertEquals(2, json.size());
        assertFalse(json.has("access_token"));
        assertFalse(json.has("minecraft_uuid"));
        assertFalse(json.has("username"));
    }

    @Test
    void treasuryOutUsesSessionProofInsteadOfBearerAuthAndRemainsThrottleLimited() {
        assertTrue(ConnectionManager.isServerScopedType("treasury_out"));
        assertFalse(ConnectionManager.isAuthenticatedOutboundType("treasury_out"));
        assertTrue(ConnectionManager.isThrottleLimitedType("treasury_out"));
        assertFalse(ConnectionManager.isSequoiaMemberOnlyType("treasury_out"));
    }

    @Test
    void dispatchesTreasuryOutRecordedWithItsRequestId() {
        AtomicReference<TreasuryOutRecordedMessage> dispatched = new AtomicReference<>();
        ConnectionManager.onTreasuryOutRecorded(dispatched::set);
        try {
            ConnectionManager.getInstance().onMessage("""
                    {
                      "type": "treasury_out_recorded",
                      "request_id": "550e8400-e29b-41d4-a716-446655440000",
                      "sheet_name": "Season 32",
                      "row": 7,
                      "amount": "2STX",
                      "payouter": "Solo",
                      "reason": "season payout",
                      "date": "2026-08-01"
                    }
                    """);

            assertEquals("550e8400-e29b-41d4-a716-446655440000", dispatched.get().requestId());
            assertEquals("Season 32", dispatched.get().sheetName());
            assertEquals(7, dispatched.get().row());
        } finally {
            ConnectionManager.onTreasuryOutRecorded(null);
            ConnectionManager.resetForTest();
        }
    }

    @Test
    void dispatchesCorrelatedTreasuryErrorBeforeGenericStatusHandling() {
        AtomicReference<TreasuryOutErrorMessage> dispatched = new AtomicReference<>();
        ConnectionManager.onTreasuryOutError(error -> {
            dispatched.set(error);
            return true;
        });
        try {
            ConnectionManager.getInstance().onMessage("""
                    {
                      "type": "error",
                      "request_id": "550e8400-e29b-41d4-a716-446655440000",
                      "status": 400,
                      "code": "invalid_request",
                      "message": "amount must use a supported denomination"
                    }
                    """);

            assertEquals("550e8400-e29b-41d4-a716-446655440000", dispatched.get().requestId());
            assertEquals("invalid_request", dispatched.get().code());
            assertEquals("amount must use a supported denomination", dispatched.get().message());
        } finally {
            ConnectionManager.onTreasuryOutError(null);
            ConnectionManager.resetForTest();
        }
    }

    @Test
    void guildAllianceSnapshotPayloadNormalizesNamesAndUsesExpectedShape() {
        List<String> guildNames = ConnectionManager.normalizeGuildAllianceNames(
                List.of(" Avicia ", "avicia", "Nefarious Ravens"));

        assertEquals(List.of("Avicia", "Nefarious Ravens"), guildNames);

        var payload = ConnectionManager.buildGuildAllianceSnapshotPayload(guildNames);
        assertEquals(1, payload.size());
        assertEquals("Avicia", payload.getAsJsonArray("guild_names").get(0).getAsString());
        assertEquals("Nefarious Ravens", payload.getAsJsonArray("guild_names").get(1).getAsString());

        assertEquals(
                0,
                ConnectionManager.buildGuildAllianceSnapshotPayload(List.of())
                        .getAsJsonArray("guild_names")
                        .size());
    }

    @Test
    void guildAllianceSnapshotRejectsMalformedOrOversizedNames() {
        assertNull(ConnectionManager.normalizeGuildAllianceNames(List.of("")));
        assertNull(ConnectionManager.normalizeGuildAllianceNames(List.of("Guild: Name")));
        assertNull(ConnectionManager.normalizeGuildAllianceNames(List.of("A".repeat(65))));
        assertNull(ConnectionManager.normalizeGuildAllianceNames(
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> "Guild " + index)
                        .toList()));
    }

}
