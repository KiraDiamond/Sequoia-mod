package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.*;

import com.seqwawa.seq.model.KnownGuildMember;
import com.seqwawa.seq.network.WynncraftGuildClient.GuildRoster;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SequoiaGuildMembershipTest {
    @Test
    void deniesColdStartAndChecksAllMembersRatherThanOnlyOnlinePlayers() {
        var response = new CompletableFuture<GuildRoster>();
        var clock = new AtomicLong(1_000);
        var membership = new SequoiaGuildMembership(() -> response, clock::get);
        assertFalse(membership.isMember("Yearnm"));
        membership.tick();
        assertFalse(membership.isMember("Yearnm"));
        response.complete(roster("SEQ", "Yearnm"));
        assertTrue(membership.isMember("yEaRnM"));
        assertFalse(membership.isMember("IChooseThou"));
        assertFalse(membership.isMember(null));
    }

    @Test
    void rejectsAnotherGuildEvenWhenItsMemberHasASequoiaDiscordRank() {
        var membership = new SequoiaGuildMembership(
                () -> CompletableFuture.completedFuture(roster("ESI", "Yearnm")), () -> 1_000L);
        membership.tick();
        assertFalse(membership.isMember("Yearnm"));
    }

    @Test
    void expiresMembershipWhileRefreshIsPending() {
        var clock = new AtomicLong(1_000);
        var response = new AtomicReference<>(CompletableFuture.completedFuture(roster("SEQ", "Yearnm")));
        var membership = new SequoiaGuildMembership(response::get, clock::get);
        membership.tick();
        assertTrue(membership.isMember("Yearnm"));
        response.set(new CompletableFuture<>());
        clock.addAndGet(SequoiaGuildMembership.REFRESH_INTERVAL_MS);
        membership.tick();
        clock.set(1_000 + SequoiaGuildMembership.MAX_AGE_MS);
        assertFalse(membership.isMember("Yearnm"));
    }

    @Test
    void failedRefreshRevokesPreviouslyKnownMembership() {
        var clock = new AtomicLong(1_000);
        var response = new AtomicReference<>(CompletableFuture.completedFuture(roster("SEQ", "Yearnm")));
        var membership = new SequoiaGuildMembership(response::get, clock::get);
        membership.tick();
        response.set(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        clock.addAndGet(SequoiaGuildMembership.REFRESH_INTERVAL_MS);
        membership.tick();
        assertFalse(membership.isMember("Yearnm"));
    }

    @Test
    void newRosterRemovesDepartedMembers() {
        var clock = new AtomicLong(1_000);
        var response = new AtomicReference<>(CompletableFuture.completedFuture(roster("SEQ", "Yearnm")));
        var membership = new SequoiaGuildMembership(response::get, clock::get);
        membership.tick();
        response.set(CompletableFuture.completedFuture(roster("SEQ", "OtherMember")));
        clock.addAndGet(SequoiaGuildMembership.REFRESH_INTERVAL_MS);
        membership.tick();
        assertFalse(membership.isMember("Yearnm"));
        assertTrue(membership.isMember("OtherMember"));
    }

    @Test
    void ignoresOldRequestAfterResetWithoutUnlockingCurrentRequest() {
        var old = new CompletableFuture<GuildRoster>();
        var current = new CompletableFuture<GuildRoster>();
        var response = new AtomicReference<>(old);
        var calls = new AtomicInteger();
        var membership = new SequoiaGuildMembership(() -> {
            calls.incrementAndGet();
            return response.get();
        }, () -> 1_000L);
        membership.tick();
        membership.reset();
        response.set(current);
        membership.tick();
        old.complete(roster("SEQ", "Yearnm"));
        membership.tick();
        assertEquals(2, calls.get());
        assertFalse(membership.isMember("Yearnm"));
        current.complete(roster("SEQ", "OtherMember"));
        assertTrue(membership.isMember("OtherMember"));
    }

    @Test
    void throttlesFailuresAndRecoversFromSynchronousExceptions() {
        var calls = new AtomicInteger();
        var clock = new AtomicLong(0);
        var membership = new SequoiaGuildMembership(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("offline");
            return CompletableFuture.completedFuture(roster("SEQ", "Yearnm"));
        }, clock::get);
        membership.tick();
        membership.tick();
        assertEquals(1, calls.get());
        assertFalse(membership.isMember("Yearnm"));
        clock.addAndGet(SequoiaGuildMembership.REFRESH_INTERVAL_MS);
        membership.tick();
        assertEquals(2, calls.get());
        assertTrue(membership.isMember("Yearnm"));
    }

    private static GuildRoster roster(String prefix, String member) {
        var known = new KnownGuildMember(member, null, null);
        return new GuildRoster(prefix, prefix, 1, List.of(), Map.of(known.key(), known));
    }
}
