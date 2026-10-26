package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PartyHealthCacheTest {
    @Test
    void resolvedAccountCannotBorrowAnotherPlayersAliasOrPrefix() {
        var visible = new java.util.HashMap<String, java.util.UUID>();
        var reyzhia = java.util.UUID.randomUUID();
        var nunot = java.util.UUID.randomUUID();
        PartyHealthCache.rememberAccount(visible, "reyzhia", reyzhia);
        assertNull(PartyHealthCache.resolveVisiblePlayerUuid(visible, "nunot"));
        assertNull(PartyHealthCache.resolveVisiblePlayerUuid(visible, "rey"));
        PartyHealthCache.rememberAccount(visible, "nunot", nunot);
        assertEquals(nunot, PartyHealthCache.resolveVisiblePlayerUuid(visible, "NUNOT"));
        PartyHealthCache.rememberAccount(visible, "nunot", reyzhia);
        assertNull(PartyHealthCache.resolveVisiblePlayerUuid(visible, "nunot"));
    }

    @Test
    void refreshesAtMostFiveTimesPerSecond() {
        long lastRefreshMs = 1_000;

        assertFalse(PartyHealthCache.isRefreshDue(lastRefreshMs + 199, lastRefreshMs));
        assertTrue(PartyHealthCache.isRefreshDue(lastRefreshMs + 200, lastRefreshMs));
    }

    @Test
    void initialRefreshIsImmediatelyDue() {
        assertTrue(PartyHealthCache.isRefreshDue(1_000, 0));
    }
}
