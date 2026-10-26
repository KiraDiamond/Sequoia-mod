package com.seqwawa.seq.network;

import com.google.gson.JsonObject;
import com.seqwawa.seq.model.PartyJoinPolicy;
import com.seqwawa.seq.model.PartyRegion;
import com.seqwawa.seq.model.PartyRole;
import com.seqwawa.seq.model.war.WarCompositionRole;
import com.seqwawa.seq.model.war.WarCompositionTargets;
import com.seqwawa.seq.model.war.WarPlannerDrafts.SupportDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.SupportSlotDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.TeamDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.TeamMemberDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.TeamMemberMoveDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.ZoneCategoryDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.ZoneDraft;
import com.seqwawa.seq.model.war.WarPlannerDrafts.ZonePlacementDraft;
import com.seqwawa.seq.model.war.WarTeamType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {

    @Test
    void princessRaidPayloadContainsOnlyEventIdentityAndRaidName() {
        UUID eventId = UUID.fromString("c15bd497-9db5-441f-9678-c2aa25dd15b6");

        JsonObject payload = ApiClient.buildPrincessRaidCompletionPayload(eventId, " The Nameless Anomaly ");

        assertEquals(eventId.toString(), payload.get("event_id").getAsString());
        assertEquals("The Nameless Anomaly", payload.get("raid_name").getAsString());
        assertEquals(2, payload.size());
        assertFalse(payload.has("username"));
        assertFalse(payload.has("player_uuid"));
        assertFalse(payload.has("raid_count"));
    }

    @Test
    void princessRaidPayloadRejectsMissingIdentityOrRaid() {
        UUID eventId = UUID.fromString("c15bd497-9db5-441f-9678-c2aa25dd15b6");

        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildPrincessRaidCompletionPayload(null, "The Nameless Anomaly"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildPrincessRaidCompletionPayload(eventId, " "));
    }

    @Test
    void resolveAuthBaseUrlStripsApiSuffix() {
        assertEquals("https://staging.seqwawa.com", ApiClient.resolveAuthBaseUrl("https://staging.seqwawa.com/api"));
    }

    @Test
    void resolveAuthBaseUrlPreservesNonApiBase() {
        assertEquals("https://staging.seqwawa.com", ApiClient.resolveAuthBaseUrl("https://staging.seqwawa.com"));
    }

    @Test
    void retryAlternateBaseWhenBearerMiddlewareInterceptsAuthRoute() {
        ApiClient.ApiException exception =
                new ApiClient.ApiException(401, "{\"code\":\"token_invalid\",\"message\":\"Missing bearer token\"}");

        assertEquals(true, ApiClient.shouldRetryAuthAtAlternateBase(exception));
    }

    @Test
    void doNotRetryAlternateBaseForUnrelatedServerErrors() {
        ApiClient.ApiException exception =
                new ApiClient.ApiException(500, "{\"message\":\"internal error\"}");

        assertEquals(false, ApiClient.shouldRetryAuthAtAlternateBase(exception));
    }

    @Test
    void achievementRequestWaitsForAValidToken() {
        CompletableFuture<String> token = new CompletableFuture<>();
        AtomicBoolean requested = new AtomicBoolean();
        CompletableFuture<String> result = ApiClient.afterValidToken(token, () -> {
            requested.set(true);
            return CompletableFuture.completedFuture("progress");
        });

        assertFalse(requested.get());
        token.complete("refreshed-token");

        assertTrue(requested.get());
        assertEquals("progress", result.join());
    }

    @Test
    void mainServerOnlyExceptionUsesExpectedStatusAndMessage() {
        ApiClient.ApiException exception = ApiClient.mainServerOnlyException();

        assertEquals(403, exception.getStatusCode());
        assertTrue(exception.getResponseBody().contains("main_server_only"));
        assertTrue(exception.getResponseBody().contains(WynncraftServerPolicy.MAIN_SERVER_ONLY_MESSAGE));
    }

    @Test
    void rewardQueueAspectRequestUsesDefaultReason() {
        JsonObject payload = ApiClient.buildRewardQueueRequestPayload("aspect", null);

        assertEquals("aspect", payload.get("type").getAsString());
        assertEquals("No reason provided.", payload.get("reason").getAsString());
    }

    @Test
    void rewardQueueTomeRequestRequiresReason() {
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildRewardQueueRequestPayload("tome", " "));
    }

    @Test
    void rewardQueueTomeRequestTrimsReason() {
        JsonObject payload = ApiClient.buildRewardQueueRequestPayload("tome", " Need guild tome ");

        assertEquals("tome", payload.get("type").getAsString());
        assertEquals("Need guild tome", payload.get("reason").getAsString());
    }

    @Test
    void rewardQueueRequestRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildRewardQueueRequestPayload("emerald", null));
    }

    @Test
    void createListingPayloadIncludesAtomicAdmissionSettings() {
        JsonObject payload = ApiClient.buildCreateListingPayload(
                List.of(11L, 12L),
                PartyRegion.EU,
                PartyRole.TANK,
                "Fast clears",
                "EU21",
                PartyJoinPolicy.INVITE_ONLY,
                2);

        assertEquals("INVITE_ONLY", payload.get("joinPolicy").getAsString());
        assertEquals(2, payload.get("reservedSlots").getAsInt());
        assertEquals(2, payload.getAsJsonArray("activityIds").size());
    }

    @Test
    void createListingPayloadDefaultsToOpenAdmission() {
        JsonObject payload = ApiClient.buildCreateListingPayload(
                List.of(11L), PartyRegion.NA, PartyRole.DPS, null, null, null, 0);

        assertEquals(PartyJoinPolicy.OPEN, PartyJoinPolicy.DEFAULT_CREATE_POLICY);
        assertEquals("OPEN", payload.get("joinPolicy").getAsString());
    }

    @Test
    void createListingPayloadRejectsNegativeReservedSlots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildCreateListingPayload(
                        List.of(11L),
                        PartyRegion.NA,
                        PartyRole.DPS,
                        null,
                        null,
                        PartyJoinPolicy.OPEN,
                        -1));
    }

    @Test
    void createListingPayloadSerializesOtherRole() {
        JsonObject payload = ApiClient.buildCreateListingPayload(
                List.of(11L),
                PartyRegion.NA,
                PartyRole.OTHER,
                null,
                null,
                PartyJoinPolicy.OPEN,
                0);

        assertEquals("OTHER", payload.get("role").getAsString());
    }

    @Test
    void modVersionHeaderConstantMatchesBackendContract() {
        assertEquals("X-Sequoia-Mod-Version", ClientVersion.MOD_VERSION_HEADER);
        assertTrue(ClientVersion.MOD_VERSION_HEADER.startsWith("X-"));
    }

    // ── Raid profiles on a separate backend ──

    @Test
    void raidProfilesAreOnASeparateBackendOnlyWhenTheUrlsDiffer() {
        assertTrue(ApiClient.isSeparateBackend("https://staging.seqwawa.com/api", "https://api.seqwawa.com/api"));
        assertFalse(ApiClient.isSeparateBackend("https://api.seqwawa.com/api", "https://api.seqwawa.com/api"));
        assertFalse(
                ApiClient.isSeparateBackend("https://api.seqwawa.com/api/", "https://API.seqwawa.com/api"),
                "a trailing slash or a change of case is the same backend");
    }

    @Test
    void signInToAnotherBackendTriesItsSiteRootThenItsApiRoot() {
        assertEquals(
                List.of("https://staging.seqwawa.com", "https://staging.seqwawa.com/api"),
                ApiClient.authRequestBaseUrlsFor("https://staging.seqwawa.com/api"));
        assertEquals(
                List.of("https://staging.seqwawa.com"),
                ApiClient.authRequestBaseUrlsFor("https://staging.seqwawa.com"),
                "no duplicate when the API root is already the site root");
    }

    @Test
    void aCallThatSucceedsDoesNotSignInAgain() {
        List<Boolean> tokenRequests = new ArrayList<>();

        String result = ApiClient.callWithTokenRetry(
                        force -> {
                            tokenRequests.add(force);
                            return CompletableFuture.completedFuture("token-1");
                        },
                        token -> CompletableFuture.completedFuture("used " + token))
                .join();

        assertEquals("used token-1", result);
        assertEquals(List.of(false), tokenRequests);
    }

    @Test
    void aRejectedTokenIsReplacedOnceAndTheCallRetried() {
        List<Boolean> tokenRequests = new ArrayList<>();
        List<String> tokensUsed = new ArrayList<>();

        String result = ApiClient.callWithTokenRetry(
                        force -> {
                            tokenRequests.add(force);
                            return CompletableFuture.completedFuture(force ? "fresh" : "stale");
                        },
                        token -> {
                            tokensUsed.add(token);
                            return "stale".equals(token)
                                    ? CompletableFuture.failedFuture(unauthorized())
                                    : CompletableFuture.completedFuture("ok with " + token);
                        })
                .join();

        assertEquals("ok with fresh", result);
        assertEquals(List.of(false, true), tokenRequests, "the second sign-in is forced");
        assertEquals(List.of("stale", "fresh"), tokensUsed);
    }

    @Test
    void aSecondRejectionIsReportedRatherThanLoopedOn() {
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<String> result = ApiClient.callWithTokenRetry(
                force -> CompletableFuture.completedFuture("token"),
                token -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(unauthorized());
                });

        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertTrue(ApiClient.isUnauthorized(failure));
        assertEquals(2, calls.get(), "one call, one retry, then stop");
    }

    @Test
    void otherFailuresAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<String> result = ApiClient.callWithTokenRetry(
                force -> CompletableFuture.completedFuture("token"),
                token -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new ApiClient.ApiException(500, "{}"));
                });

        assertThrows(CompletionException.class, result::join);
        assertEquals(1, calls.get());
    }

    @Test
    void aFailedSignInIsReportedWithoutCallingTheBackend() {
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<String> result = ApiClient.callWithTokenRetry(
                force -> CompletableFuture.failedFuture(new IllegalStateException("not linked")),
                token -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture("unreachable");
                });

        assertThrows(CompletionException.class, result::join);
        assertEquals(0, calls.get());
    }

    private static ApiClient.ApiException unauthorized() {
        return new ApiClient.ApiException(401, "{\"code\":\"token_invalid\",\"message\":\"Invalid token\"}");
    }

    @Test
    void warAvailabilityPayloadUsesSnakeCaseAndBounds() {
        JsonObject payload = ApiClient.buildWarAvailabilityPayload(90);

        assertEquals(90, payload.get("duration_minutes").getAsInt());
        assertDoesNotThrow(() -> ApiClient.buildWarAvailabilityPayload(1));
        assertDoesNotThrow(() -> ApiClient.buildWarAvailabilityPayload(1440));
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildWarAvailabilityPayload(0));
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildWarAvailabilityPayload(1441));
    }

    @Test
    void warTerritoryQueueObservationPayloadMatchesSchemaV1Contract() {
        JsonObject payload = ApiClient.buildWarTerritoryQueueObservationPayload(
                " xiaolongbao ", " Soup Person ", " Alekin ", "Very High");

        assertEquals(4, payload.size());
        assertEquals("xiaolongbao", payload.get("minecraft_username").getAsString());
        assertEquals("Soup Person", payload.get("nickname").getAsString());
        assertEquals("Alekin", payload.get("territory").getAsString());
        assertEquals("Very High", payload.get("defense_rating").getAsString());
    }

    @Test
    void warTerritoryQueueObservationPayloadKeepsMissingNicknameExplicit() {
        JsonObject payload = ApiClient.buildWarTerritoryQueueObservationPayload(
                "xiaolongbao", null, "Alekin", "Low");

        assertTrue(payload.has("nickname"));
        assertTrue(payload.get("nickname").isJsonNull());
    }

    @Test
    void warTerritoryQueueObservationPayloadCarriesOptionalAuthoritativeDuration() {
        JsonObject payload = ApiClient.buildWarTerritoryQueueObservationPayload(
                "xiaolongbao", null, "Sulphuric Hollow", "Very Low", 420);

        assertEquals(5, payload.size());
        assertEquals(420, payload.get("queue_duration_seconds").getAsInt());
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        "xiaolongbao", null, "Sulphuric Hollow", "Very Low", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        "xiaolongbao", null, "Sulphuric Hollow", "Very Low", 3_601));
    }

    @Test
    void warTerritoryQueueTimerOnlyPayloadOmitsUnresolvedIdentityAndDefense() {
        JsonObject payload = ApiClient.buildWarTerritoryQueueObservationPayload(
                null, null, " Sulphuric Hollow ", null, 420);

        assertEquals(2, payload.size());
        assertEquals("Sulphuric Hollow", payload.get("territory").getAsString());
        assertEquals(420, payload.get("queue_duration_seconds").getAsInt());
        assertFalse(payload.has("minecraft_username"));
        assertFalse(payload.has("nickname"));
        assertFalse(payload.has("defense_rating"));
    }

    @Test
    void warTerritoryQueuePayloadRejectsInvalidPartialObservationShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        null, null, "Alekin", "Low", 420));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        "xiaolongbao", null, "Alekin", null, 420));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        null, "Soup Person", "Alekin", null, 420));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        null, null, "Alekin", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        null, null, "Alekin", null, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        null, null, "Alekin", null, 3_601));
    }

    @Test
    void warTerritoryQueueObservationPayloadRejectsInvalidUntrustedFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload("bad name", null, "Alekin", "Low"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload("xiaolongbao", null, " ", "Low"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiClient.buildWarTerritoryQueueObservationPayload(
                        "xiaolongbao", null, "Alekin", "Extreme"));
    }

    @Test
    void warTerritoryQueueParticipantPathTargetsAuthenticatedCallerForPutAndDelete() {
        assertEquals(
                "/war-planner/territory-queues/42/participants/me",
                ApiClient.warTerritoryQueueParticipantPath(42));
        assertThrows(IllegalArgumentException.class, () -> ApiClient.warTerritoryQueueParticipantPath(0));
    }

    @Test
    void warCompositionRolePayloadIsOrderedDeduplicatedAndAllowsEmpty() {
        JsonObject payload = ApiClient.buildWarCompositionRolesPayload(
                List.of(WarCompositionRole.TANK, WarCompositionRole.SOLO, WarCompositionRole.TANK));

        assertEquals("SOLO", payload.getAsJsonArray("roles").get(0).getAsString());
        assertEquals("TANK", payload.getAsJsonArray("roles").get(1).getAsString());
        assertEquals(2, payload.getAsJsonArray("roles").size());
        assertTrue(ApiClient.buildWarCompositionRolesPayload(List.of()).getAsJsonArray("roles").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildWarCompositionRolesPayload(null));
    }

    @Test
    void warTeamUpdatePayloadIsAtomicAndVersioned() {
        TeamDraft draft = new TeamDraft(
                WarTeamType.FFA,
                7L,
                new WarCompositionTargets(1, 3, 1),
                List.of(
                        new TeamMemberDraft("leader-uuid"),
                        new TeamMemberDraft("eco-uuid")));

        JsonObject payload = ApiClient.buildWarTeamPayload(draft, true);

        assertEquals("FFA", payload.get("team_type").getAsString());
        assertEquals(7L, payload.get("version").getAsLong());
        assertEquals(1, payload.getAsJsonObject("composition_targets").get("solo").getAsInt());
        assertEquals(3, payload.getAsJsonObject("composition_targets").get("dps").getAsInt());
        assertEquals(1, payload.getAsJsonObject("composition_targets").get("tank").getAsInt());
        assertEquals("leader-uuid", payload.getAsJsonArray("members")
                .get(0).getAsJsonObject().get("player_uuid").getAsString());
        assertFalse(ApiClient.buildWarTeamPayload(new TeamDraft(WarTeamType.FFA, null, draft.members()), false)
                .has("version"));
    }

    @Test
    void warTeamMemberMovePayloadKeepsNullableSidesAndBothVersions() {
        JsonObject betweenTeams = ApiClient.buildWarTeamMemberMovePayload(
                new TeamMemberMoveDraft(7L, 3L, 9L, 5L));
        JsonObject fromRoster = ApiClient.buildWarTeamMemberMovePayload(
                new TeamMemberMoveDraft(null, null, 9L, 5L));

        assertEquals(7L, betweenTeams.get("source_team_id").getAsLong());
        assertEquals(3L, betweenTeams.get("source_version").getAsLong());
        assertEquals(9L, betweenTeams.get("target_team_id").getAsLong());
        assertEquals(5L, betweenTeams.get("target_version").getAsLong());
        assertTrue(fromRoster.get("source_team_id").isJsonNull());
        assertTrue(fromRoster.get("source_version").isJsonNull());
        assertEquals(9L, fromRoster.get("target_team_id").getAsLong());
    }

    @Test
    void warPlannerDeletesCarryTheDisplayedVersion() {
        assertEquals("/war-planner/teams/7?version=3", ApiClient.versionedWarPlannerPath("/war-planner/teams/7", 3));
        assertEquals("/war-planner/zones/9?version=5", ApiClient.versionedWarPlannerPath("/war-planner/zones/9", 5));
        assertThrows(IllegalArgumentException.class,
                () -> ApiClient.versionedWarPlannerPath("/war-planner/teams/7", 0));
    }

    @Test
    void warZonePayloadKeepsMultipleNumericAssignments() {
        JsonObject assigned = ApiClient.buildWarZonePayload(
                new ZoneDraft("North", "#AABBCC", List.of(42L, 43L), 3L, List.of("Ragni")), true);
        JsonObject unassigned = ApiClient.buildWarZonePayload(
                new ZoneDraft("North", "#AABBCC", List.of(), null, List.of("Ragni")), false);

        assertEquals(42L, assigned.getAsJsonArray("assigned_team_ids").get(0).getAsLong());
        assertEquals("#AABBCC", assigned.get("color").getAsString());
        assertEquals(3L, assigned.get("version").getAsLong());
        assertEquals("Ragni", assigned.getAsJsonArray("territories").get(0).getAsString());
        assertTrue(unassigned.getAsJsonArray("assigned_team_ids").isEmpty());
        assertFalse(unassigned.has("version"));
    }

    @Test
    void warHqTerritoryPayloadCanAtomicallyReplaceOrClearTheMarker() {
        JsonObject assigned = ApiClient.buildWarHqTerritoryPayload("Detlas", 7);
        JsonObject cleared = ApiClient.buildWarHqTerritoryPayload(null, 8);

        assertEquals("Detlas", assigned.get("territory").getAsString());
        assertEquals(7, assigned.get("version").getAsLong());
        assertTrue(cleared.get("territory").isJsonNull());
        assertEquals(8, cleared.get("version").getAsLong());
        assertThrows(IllegalArgumentException.class, () -> ApiClient.buildWarHqTerritoryPayload("Detlas", 0));
    }

    @Test
    void warZoneCategoryAndPlacementPayloadsKeepVersionsAndNullableCategory() {
        JsonObject created = ApiClient.buildWarZoneCategoryPayload(
                new ZoneCategoryDraft("Frontline", null), false);
        JsonObject renamed = ApiClient.buildWarZoneCategoryPayload(
                new ZoneCategoryDraft("North front", 3L), true);
        JsonObject categorized = ApiClient.buildWarZonePlacementPayload(
                new ZonePlacementDraft(4L, 1, 7L));
        JsonObject uncategorized = ApiClient.buildWarZonePlacementPayload(
                new ZonePlacementDraft(null, 0, 8L));

        assertEquals("Frontline", created.get("name").getAsString());
        assertFalse(created.has("version"));
        assertEquals(3L, renamed.get("version").getAsLong());
        assertEquals(4L, categorized.get("category_id").getAsLong());
        assertEquals(1, categorized.get("position").getAsInt());
        assertEquals(7L, categorized.get("version").getAsLong());
        assertTrue(uncategorized.get("category_id").isJsonNull());
    }

    @Test
    void warSupportPayloadKeepsTheFourSharedSlotsSeparateFromParties() {
        JsonObject payload = ApiClient.buildWarSupportPayload(new SupportDraft(
                4L,
                List.of(
                        new SupportSlotDraft("LEAD", "lead-uuid"),
                        new SupportSlotDraft("ECO_1", "eco-uuid"),
                        new SupportSlotDraft("ECO_2", null))));

        assertEquals(4L, payload.get("version").getAsLong());
        assertEquals("LEAD", payload.getAsJsonArray("slots").get(0).getAsJsonObject().get("code").getAsString());
        assertEquals(
                "eco-uuid",
                payload.getAsJsonArray("slots").get(1).getAsJsonObject().get("player_uuid").getAsString());
        assertTrue(payload.getAsJsonArray("slots").get(2).getAsJsonObject().get("player_uuid").isJsonNull());
    }
    @org.junit.jupiter.api.Test
    void minecraftCompletionSendsClientNonceWithoutMinecraftCredentials() {
        var request = new com.seqwawa.seq.network.auth.MinecraftAuthCompleteRequest(
                "challenge", "SyntheticPlayer", "client-proof-nonce");
        var body = ApiClient.minecraftAuthCompleteBody(request);
        org.junit.jupiter.api.Assertions.assertEquals(3, body.size());
        org.junit.jupiter.api.Assertions.assertEquals("challenge", body.get("challenge_id").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals("SyntheticPlayer", body.get("username").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals("client-proof-nonce", body.get("client_nonce").getAsString());
    }

}
