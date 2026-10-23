package com.seqwawa.seq.managers;

import static com.seqwawa.seq.managers.PacketNameResolver.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PacketNameResolverTest {
    @BeforeEach void reset() {
        NicknameResolverCache.reset();
        NicknameResolverCache.clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    }
    @AfterEach void cleanup() {
        NicknameResolverCache.clock = Clock.systemUTC();
        NicknameResolverCache.reset();
    }
    private static Component insertion(String alias, String account) {
        return Component.literal(alias).withStyle(Style.EMPTY.withInsertion(account));
    }
    private static Style hover(String display, String account) {
        return Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal(display + "'s real name is " + account)));
    }
    private static Resolution resolve(Component name, Policy policy) {
        var resolver = PacketNameResolver.from(name);
        return resolver.resolve(0, resolver.text().length(), policy, "test.player");
    }
    private static Resolution cached(String name, Policy policy) {
        return resolve(Component.literal(name).withStyle(Style.EMPTY.withItalic(true)), policy);
    }
    private static void advance(int minutes) {
        NicknameResolverCache.clock = Clock.offset(NicknameResolverCache.clock, Duration.ofMinutes(minutes));
    }

    @Test void cacheCapacityDoesNotPreventStrongerEvidenceReplacingAnExistingKey() {
        for (int i = 0; i < 2048; i++) IdentityTestSupport.remember("Alias " + i, "reyzhia");
        var resolver = PacketNameResolver.from(Component.literal("Alias 0").withStyle(hover("Alias 0", "nunot")));
        resolver.observe(0, resolver.text().length(), Policy.CHAT, "chat.speaker");
        assertEquals("nunot", cached("Alias 0", Policy.ALIAS).username());
    }

    @Test void resolutionAndRenderingDoNotTeachAliases() {
        assertEquals("nunot", resolve(insertion("Some Nick", "nunot"), Policy.CHAT).username());
        assertEquals(State.UNRESOLVED, cached("Some Nick", Policy.ALIAS).state());
    }
    @Test void exactCacheAndExplicitTruncationDoNotPromotePrefixes() {
        IdentityTestSupport.remember("Some Long Nick", "nunot");
        assertEquals(State.UNRESOLVED, cached("Some Long", Policy.CHAT).state());
        assertEquals("nunot", cached("Some Long", Policy.TRUNCATED).username());
        assertEquals(State.UNRESOLVED, cached("Some Long", Policy.ALIAS).state());
        IdentityTestSupport.remember("Some Long Other", "reyzhia");
        assertEquals(State.AMBIGUOUS, cached("Some Long", Policy.TRUNCATED).state());
    }
    @Test void readsDoNotRenewTheOriginalObservation() {
        IdentityTestSupport.remember("Some Nick", "nunot");
        advance(9);
        assertEquals("nunot", cached("Some Nick", Policy.ALIAS).username());
        advance(2);
        assertEquals(State.UNRESOLVED, cached("Some Nick", Policy.ALIAS).state());
    }
    @Test void conflictingObservationsStayQuarantinedWithoutRenewal() {
        IdentityTestSupport.remember("Some Nick", "nunot");
        advance(8);
        IdentityTestSupport.remember("Some Nick", "reyzhia");
        var result = cached("Some Nick", Policy.ALIAS);
        assertEquals(State.AMBIGUOUS, result.state());
        assertNull(result.username());
        assertEquals(List.of("nunot", "reyzhia"), result.evidence());
        advance(3);
        assertEquals(State.UNRESOLVED, cached("Some Nick", Policy.ALIAS).state());
    }
    @Test void explicitRevealSupersedesWeakerNicknameInsertion() {
        IdentityTestSupport.remember("Reyzhia", "reyzhia");
        var resolver = PacketNameResolver.from(Component.literal("Reyzhia").withStyle(
                hover("Reyzhia", "nunot").withInsertion("reyzhia").withItalic(true)));
        var result = resolver.observe(0, resolver.text().length(), Policy.CHAT, "chat.speaker");
        assertEquals("nunot", result.username());
        assertEquals(Source.HOVER, result.source());
        IdentityTestSupport.remember("Reyzhia", "reyzhia");
        assertEquals("nunot", cached("Reyzhia", Policy.ALIAS).username());
    }
    @Test void equallyAuthoritativeRevealsRemainAmbiguous() {
        Component name = Component.empty().append(Component.literal("Some ").withStyle(hover("Some Nick", "nunot")))
                .append(Component.literal("Nick").withStyle(hover("Some Nick", "reyzhia")));
        assertEquals(State.AMBIGUOUS, resolve(name, Policy.CHAT).state());
        assertNull(resolve(name, Policy.CHAT).username());
    }
    @Test void rankAssignmentRequiresAgreementAmongItsMetadata() {
        Component name = Component.literal("Nickname").withStyle(hover("Nickname", "nunot").withInsertion("OtherPlayer"));
        assertEquals(State.AMBIGUOUS, resolve(name, Policy.METADATA).state());
        assertEquals(State.UNRESOLVED, resolve(Component.literal("Nickname(nunot)"), Policy.METADATA).state());
    }
    @Test void rootInsertionAndSubjectlessHoverCannotClaimAChildName() {
        for (Style style : List.of(Style.EMPTY.withInsertion("RaidKeeper"), Style.EMPTY.withHoverEvent(
                new HoverEvent.ShowText(Component.literal("Real Username: RaidKeeper"))))) {
            var resolver = PacketNameResolver.from(Component.empty().withStyle(style)
                    .append("Some Nick").append(": hello"));
            assertEquals(State.UNRESOLVED, resolver.resolve(0, 9, Policy.CHAT, "chat.speaker").state());
        }
    }
    @Test void metadataOwnedByANameContainerCanCoverSplitChildren() {
        Component name = Component.empty().withStyle(Style.EMPTY.withInsertion("nunot"))
                .append("Some ").append("Nick");
        var resolver = PacketNameResolver.from(Component.empty().append(name).append(": hello"));
        assertEquals("nunot", resolver.resolve(0, 9, Policy.CHAT, "chat.speaker").username());
    }
    @Test void inheritedHoverMustNameTheRequestedPerson() {
        var resolver = PacketNameResolver.from(Component.empty().withStyle(hover("OtherNick", "RaidKeeper"))
                .append("Some Nick").append(": hello OtherNick"));
        assertEquals(State.UNRESOLVED, resolver.resolve(0, 9, Policy.CHAT, "chat.speaker").state());
    }
    @Test void sessionResetRejectsOldResolversAndOldObservations() {
        var resolver = PacketNameResolver.from(insertion("Some Nick", "nunot"));
        var old = resolver.resolve(0, 9, Policy.CHAT, "chat.speaker");
        NicknameResolverCache.reset();
        NicknameResolverCache.observe(old);
        assertEquals(State.UNRESOLVED, resolver.observe(0, 9, Policy.CHAT, "chat.speaker").state());
        assertEquals(State.UNRESOLVED, cached("Some Nick", Policy.ALIAS).state());
    }
    @Test void syntaxOnlyMeansAnAccountWhenTheFormatDeclaresIt() {
        assertEquals("GaztheCat", resolve(Component.literal("GaztheCat"), Policy.CHAT).username());
        assertNull(resolve(Component.literal("GaztheCat").withStyle(Style.EMPTY.withItalic(true)), Policy.CHAT).username());
        assertNull(resolve(Component.literal("Some Nick"), Policy.CHAT).username());
        assertNull(resolve(Component.literal("GaztheCat"), Policy.METADATA).username());
    }
    @Test void slashFormatsRequireExactlyOneAccountCandidate() {
        assertEquals("nunot", resolve(Component.literal("nunot/Some Nick"), Policy.CHAT).username());
        assertEquals(State.AMBIGUOUS, resolve(Component.literal("nunot/reyzhia"), Policy.CHAT).state());
    }
}
