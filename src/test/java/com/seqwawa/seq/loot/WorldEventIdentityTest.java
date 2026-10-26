package com.seqwawa.seq.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldEventIdentityTest {
    private final WorldEventIdentity identity = new WorldEventIdentity();

    private WorldEventIdentity.Candidate candidate(String name, double x, double z) {
        return new WorldEventIdentity.Candidate(name, "native_label", x, z);
    }

    private void observe(String name, long now) {
        identity.observe(List.of(candidate(name, 10, 10)), now, 0, 0);
    }

    @Test
    void stagesNameBeforeEntryAndRetainsItWhenLabelDisappearsFor65Seconds() {
        observe("Known event", 1000);
        identity.observe(List.of(), 2000, 0, 0);
        identity.enter(3000, 0, 0);
        identity.observe(List.of(), 67_000, 0, 0);
        assertEquals("identified", identity.state(68_000));
        assertEquals("Known event", identity.complete(68_000));
        assertNull(identity.complete(68_001));
    }

    @Test
    void rejectsCompletionOutsideRadiusAndDoesNotReuseOldEventAfterCompletion() {
        observe("Known event", 1000);
        assertNull(identity.complete(2000));
        identity.enter(3000, 0, 0);
        assertNull(identity.complete(4000));
        observe("A", 5000);
        identity.enter(6000, 0, 0);
        assertEquals("A", identity.complete(7000));
        identity.enter(8000, 0, 0);
        assertNull(identity.complete(9000));
    }

    @Test
    void rejectsStaleDistantAndMovedCandidatesButAcceptsFreshUniqueOne() {
        observe("Old", 1000);
        identity.enter(17_000, 0, 0);
        assertEquals("staged_name_expired", identity.state(17_001));
        assertNull(identity.complete(18_000));
        observe("Nearby before teleport", 20_000);
        identity.enter(21_000, 200, 200);
        assertEquals("staged_name_out_of_range", identity.state(21_001));
        assertNull(identity.complete(22_000));
        identity.observe(List.of(candidate("Far label", 200, 200)), 30_000, 0, 0);
        identity.enter(31_000, 0, 0);
        assertNull(identity.complete(32_000));
        observe("Fresh", 40_000);
        identity.enter(41_000, 0, 0);
        assertEquals("Fresh", identity.complete(42_000));
    }

    @Test
    void supportsLateExplicitNameWhenEnteringBeforeLabelsLoad() {
        identity.enter(1000, 0, 0);
        assertEquals("no_explicit_name", identity.state(1001));
        observe("Late label", 2000);
        assertEquals("Late label", identity.complete(67_000));
    }

    @Test
    void ambiguousOrConflictingNamesInvalidateWholeSessionWithoutSwitchingEvents() {
        observe("A", 1000);
        identity.enter(2000, 0, 0);
        observe("B", 3000);
        assertEquals("conflicting_names", identity.state(3001));
        observe("A", 4000);
        assertNull(identity.complete(5000));
        identity.enter(6000, 0, 0);
        identity.observe(List.of(candidate("A", 0, 0), candidate("B", 0, 0)), 7000, 0, 0);
        observe("A", 8000);
        assertNull(identity.complete(9000));
    }

    @Test
    void sameNameFromBothSourcesIsUnambiguousAndDuplicateEntryPreservesIdentity() {
        identity.observe(List.of(candidate("A", 10, 10),
                new WorldEventIdentity.Candidate("A", "wynntils", 0, 0)), 1000, 0, 0);
        identity.enter(2000, 0, 0);
        assertEquals("wynntils", identity.source());
        identity.enter(67_000, 0, 0);
        assertEquals(65_000, identity.sessionAgeMs(67_000));
        assertEquals("A", identity.complete(68_000));
    }

    @Test
    void sessionHasHardDeadlineEvenIfLabelsRemainAndEntryIsRepeated() {
        observe("A", 1000);
        identity.enter(2000, 0, 0);
        observe("A", 3_602_001);
        identity.enter(3_602_002, 0, 0);
        assertEquals("session_expired", identity.state(3_602_003));
        assertNull(identity.complete(3_602_004));
    }

    @Test
    void radiusExitAndWorldAccountOrPreferenceResetClearStagedAndActiveNames() {
        observe("Staged", 1000);
        identity.leave();
        identity.enter(2000, 0, 0);
        assertNull(identity.complete(3000));
        observe("Active", 4000);
        identity.enter(5000, 0, 0);
        identity.leave();
        assertNull(identity.complete(6000));
    }
}
