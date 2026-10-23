package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.managers.GuildRankNametagDecorator.Member;
import com.seqwawa.seq.model.DiscordRank;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.ComponentTextEditor;
import com.seqwawa.seq.utils.RankGradientAnimation;
import com.seqwawa.seq.utils.WynnPillGlyphs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringDecomposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GuildRankNametagDecoratorTest {

    private static final UUID ROSTER_UUID = UUID.fromString("95f1b342-d7f5-466e-b1c8-32f0b42214db");

    private static final RankPresentation DRYAD =
            new RankPresentation(new DiscordRank("rank.dryad", "Dryad", 95), ColorRamp.of(0x2ECC71));
    private static final Member ARC = new Member("ArcLeRetour", DRYAD);
    private static final Function<String, Member> MEMBERS =
            name -> "arcleretour".equals(name) ? ARC : null;

    /** Wynncraft's account rank badge, as it sits on a nametag above a head. */
    private static final String CHAMPION_BADGE = WynnPillGlyphs.encodePlainPill("champion");

    /** A private-use glyph standing for another mod's nametag decoration. */
    private static final String OTHER_MOD_GLYPH = "\uE0A0";

    private static final String WYNNTILS_LOGO = "\uE100";
    private static final Style WYNNTILS_LOGO_STYLE = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("wynntils", "nametag")))
            .withColor(0x55FFFF);

    /** Gold, as Wynncraft draws an account rank badge. */
    private static final int WYNNCRAFT_BADGE_COLOR = 0xFFAA00;

    /** Colours pinned by the decorations a test built, handed back when it ends. */
    private final List<TextColor> pinned = new ArrayList<>();

    @AfterEach
    void releasePinnedColors() {
        RankGradientAnimation.release(pinned);
        pinned.clear();
    }

    /** Decorates as the render hook does, keeping the pinned colours for cleanup. */
    private Component decorate(Component nameTag) {
        GuildRankNametagDecorator.Decoration decoration =
                GuildRankNametagDecorator.decorate(nameTag, MEMBERS);
        pinned.addAll(decoration.colors());
        return decoration.component();
    }

    @Test
    void replacesTheWynncraftBadgeWithTheSequoiaRank() {
        Component nameTag = Component.literal(CHAMPION_BADGE + " ArcLeRetour");

        Component decorated = decorate(nameTag);

        assertNotSame(nameTag, decorated);
        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(decorated.getString().endsWith(" ArcLeRetour"), decorated.getString());
    }

    @Test
    void consumesLegacyColorCodesBeforeReplacingTheBadgeAndPaintingTheName() {
        Component nameTag = Component.literal("\u00a76" + CHAMPION_BADGE + " \u00a7fArcLeRetour");

        Component decorated = decorate(nameTag);

        assertNotSame(nameTag, decorated);
        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(StringDecomposer.getPlainText(decorated).endsWith(" ArcLeRetour"));
        assertFalse(decorated.getString().contains("\u00a7"));
        assertFalse(decorated.getString().contains("fArcLeRetour"));
    }

    @Test
    void consumesLegacyColorCodesWhenAddingABadge() {
        Component decorated = decorate(Component.literal("\u00a7fArcLeRetour"));

        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(StringDecomposer.getPlainText(decorated).endsWith(" ArcLeRetour"));
        assertFalse(decorated.getString().contains("\u00a7"));
    }

    @Test
    void leavesAPlayerOutsideTheRosterAlone() {
        Component nameTag = Component.literal(CHAMPION_BADGE + " SomeoneElse");

        assertSame(nameTag, decorate(nameTag));
    }

    @Test
    void addsTheRankWhenTheAccountHasNoBadge() {
        Component nameTag = Component.literal("ArcLeRetour");

        Component decorated = decorate(nameTag);

        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(decorated.getString().endsWith(" ArcLeRetour"), decorated.getString());
    }

    /**
     * Nametags are submitted every frame, and a mod may hand back one this already
     * rewrote; recognising its own pill keeps it from stacking a second one.
     */
    @Test
    void leavesANametagItAlreadyDecoratedAlone() {
        Component decorated =
                decorate(Component.literal(CHAMPION_BADGE + " ArcLeRetour"));

        assertSame(decorated, decorate(decorated));
    }

    @Test
    void recognisesAnExistingMultiWordRankPill() {
        RankPresentation strategist = new RankPresentation(
                new DiscordRank("rank.upper_strategist", "Upper Strategist", 110),
                ColorRamp.of(0x2ECC71));
        Member ranked = new Member("ArcLeRetour", strategist);
        Component nameTag = Component.literal(
                WynnPillGlyphs.encodePlainPill("Upper Strategist") + " ArcLeRetour");

        GuildRankNametagDecorator.Decoration decoration = GuildRankNametagDecorator.decorate(
                nameTag, name -> "arcleretour".equals(name) ? ranked : null);

        assertSame(nameTag, decoration.component());
        assertEquals(List.of(), decoration.colors());
    }

    /**
     * Only the badge standing next to the name is the rank badge. Another mod's
     * marker in front of it, such as a starred profile, has to survive.
     */
    @Test
    void replacesOnlyTheBadgeNextToTheName() {
        Component nameTag =
                Component.literal(OTHER_MOD_GLYPH + " " + CHAMPION_BADGE + " ArcLeRetour");

        Component decorated = decorate(nameTag);

        assertTrue(decorated.getString().startsWith(OTHER_MOD_GLYPH + " "), decorated.getString());
        assertEquals(List.of("dryad"), badgeLabels(decorated));
    }

    @Test
    void keepsTheWynntilsLogoWhenTheAccountHasNoBadge() {
        assertWynntilsLogoSurvives(" ", "");
    }

    @Test
    void keepsTheWynntilsLogoWhenReplacingAnAccountBadge() {
        assertWynntilsLogoSurvives(" ", CHAMPION_BADGE + " ");
    }

    @Test
    void keepsTheWynntilsLogoWhenReplacingALayeredAccountBadge() {
        assertWynntilsLogoSurvives(" ", layeredBadge("champion") + " ");
    }

    @Test
    void keepsTheWynntilsLogoEvenWithoutASeparatingSpace() {
        assertWynntilsLogoSurvives("", layeredBadge("champion") + " ");
    }

    private void assertWynntilsLogoSurvives(String separator, String badge) {
        Component nameTag = Component.empty()
                .append(Component.literal(WYNNTILS_LOGO).withStyle(WYNNTILS_LOGO_STYLE))
                .append(separator)
                .append(Component.literal(badge + "ArcLeRetour"));

        Component decorated = decorate(nameTag);

        assertTrue(decorated.getString().startsWith(WYNNTILS_LOGO + separator));
        ComponentTextEditor.Fragment logo = ComponentTextEditor.flatten(decorated).getFirst();
        assertEquals(WYNNTILS_LOGO, logo.text());
        assertEquals(WYNNTILS_LOGO_STYLE, logo.style(), "the logo keeps its font and colour");
        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(decorated.getString().endsWith(" ArcLeRetour"));
        assertSame(decorated, decorate(decorated), "a second pass must not duplicate the rank");
    }

    /** A badge another mod appends after the name is not a rank badge either. */
    @Test
    void keepsDecorationsThatFollowTheName() {
        Component nameTag =
                Component.literal(CHAMPION_BADGE + " ArcLeRetour " + OTHER_MOD_GLYPH);

        Component decorated = decorate(nameTag);

        assertTrue(
                decorated.getString().endsWith(" ArcLeRetour " + OTHER_MOD_GLYPH), decorated.getString());
        assertEquals(List.of("dryad"), badgeLabels(decorated));
    }

    /**
     * A mod that rebuilds the nametag from its own data leaves an unranked account
     * with nothing but its name, split across its own pieces; the rank still has to
     * reach the tag it produced.
     */
    @Test
    void decoratesARebuiltNametagThatKeptOnlyTheName() {
        Component nameTag = Component.literal("Arc").append(Component.literal("LeRetour"));

        Component decorated = decorate(nameTag);

        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertTrue(decorated.getString().endsWith(" ArcLeRetour"), decorated.getString());
    }

    /**
     * Wynncraft's layered badge is a foreground pill, a back-advance and a shadow
     * repeating the label, joined by supplementary-plane characters. Replacing only
     * the pill used to leave the shadow standing next to the new rank, spelling the
     * old one.
     */
    @Test
    void removesEveryLayerOfALayeredBadge() {
        Component nameTag = Component.literal(layeredBadge("champion") + " ArcLeRetour");

        Component decorated = decorate(nameTag);

        assertEquals(List.of("dryad"), badgeLabels(decorated));
        assertEquals(
                1,
                WynnPillGlyphs.findPills(decorated.getString()).size(),
                "the old badge left a layer behind: " + decorated.getString());
        assertTrue(
                decorated.getString().indexOf(WynnPillGlyphs.BACKGROUND) >= 0,
                "the badge is chat's pill, background blocks and all");
    }

    /** The name carries the rank colours too, so the tag reads as one label. */
    @Test
    void paintsTheNameInTheRankColour() {
        Component decorated =
                decorate(Component.literal(CHAMPION_BADGE + " ArcLeRetour"));

        assertEquals(List.of(0x2ECC71), nameColors(decorated), "the name wears the rank colour");
        assertTrue(
                rankLabelColors(decorated).stream().allMatch(color -> color.getValue() == 0x2ECC71),
                "and so does the badge in front of it");
    }

    /**
     * A nametag stands on screen for as long as its owner is in sight, far longer than
     * the chat line the animation registry is sized for. Its colours have to stay
     * registered through any amount of chat: an evicted stop stops following the
     * settings, and since eviction takes one glyph at a time, the pill used to come
     * apart into differently coloured pieces as one moved.
     */
    @Test
    void keepsItsColoursWhenChatFillsTheAnimationRegistry() {
        Component nameTag = Component.literal(CHAMPION_BADGE)
                .withStyle(style -> style.withColor(WYNNCRAFT_BADGE_COLOR))
                .append(Component.literal(" ArcLeRetour"));
        List<TextColor> backgrounds = rankLabelColors(decorate(nameTag));
        assertFalse(backgrounds.isEmpty());

        fillAnimationRegistry();

        Setting.BooleanSetting previous = SeqClient.colorRankPillsSetting;
        try {
            SeqClient.colorRankPillsSetting = new Setting.BooleanSetting("color_rank_pills", "chat", false);
            for (TextColor background : backgrounds) {
                assertEquals(
                        WYNNCRAFT_BADGE_COLOR,
                        RankGradientAnimation.animate(background).getValue(),
                        "the pill stopped answering to the settings, so its stop was evicted");
            }
        } finally {
            SeqClient.colorRankPillsSetting = previous;
        }
    }

    /**
     * Minecraft draws every nametag twice, and styled text takes the alpha of the pass
     * drawing it. Sequoia's own text is drawn at full alpha in both, which needs it to
     * be recognisable as its own; see {@code SeeThroughTextPass}.
     */
    @Test
    void marksItsColoursAsSequoiaDecorations() {
        Component decorated = decorate(Component.literal(CHAMPION_BADGE + " ArcLeRetour"));

        List<TextColor> rankColors = rankLabelColors(decorated);
        assertFalse(rankColors.isEmpty());
        for (TextColor rankColor : rankColors) {
            assertTrue(RankGradientAnimation.isDecorationColor(rankColor), "an unrecognised rank colour");
        }
        assertFalse(
                RankGradientAnimation.isDecorationColor(TextColor.fromRgb(0x123456)),
                "a colour Sequoia never minted");
    }

    /** Chat traffic well past the registry's bound, to evict anything evictable. */
    private static void fillAnimationRegistry() {
        RankGradientAnimation.batchRegistrations(() -> {
            for (int index = 0; index < 5000; index++) {
                RankGradientAnimation.colorAt(ColorRamp.of(0xFF0000), 0d);
            }
            return null;
        });
    }

    @Test
    void findsTheNameBehindTheBadgeGlyphs() {
        String text = CHAMPION_BADGE + " ArcLeRetour";

        GuildRankNametagDecorator.DisplayedName name =
                GuildRankNametagDecorator.displayedName(text, MEMBERS);

        assertNotNull(name);
        assertEquals(text.indexOf("ArcLeRetour"), name.start());
        assertEquals(ARC, name.member());
    }

    @Test
    void readsEveryNameANametagCouldBeKnownBy() {
        assertEquals(
                List.of("Nick", "ArcLeRetour"),
                GuildRankNametagDecorator.nameCandidates(CHAMPION_BADGE + " Nick(ArcLeRetour)"));
    }

    /** One letter is a decoration, not a name, and twenty characters is not one either. */
    @Test
    void ignoresTextThatCannotBeAnAccountName() {
        assertEquals(List.of(), GuildRankNametagDecorator.nameCandidates("A b [] 1"));
        assertEquals(List.of(), GuildRankNametagDecorator.nameCandidates("ThisNameIsFarTooLong"));
    }

    @Test
    void findsNoNameOnATagThatBelongsToNobodyKnown() {
        assertNull(GuildRankNametagDecorator.displayedName("Wandering Merchant", MEMBERS));
    }

    /** Colours of the fragments holding the displayed name, which is mixed case. */
    private static List<Integer> nameColors(Component component) {
        return ComponentTextEditor.flatten(component).stream()
                .filter(fragment -> fragment.text().chars().allMatch(Character::isLetterOrDigit))
                .filter(fragment -> fragment.text().chars().anyMatch(Character::isLowerCase))
                .map(fragment -> fragment.style().getColor().getValue())
                .toList();
    }

    /** Colours of the pill's background blocks, which carry the rank's palette. */
    private static List<TextColor> rankLabelColors(Component component) {
        return ComponentTextEditor.flatten(component).stream()
                .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.BACKGROUND) >= 0)
                .map(fragment -> fragment.style().getColor())
                .toList();
    }

    /** The ranks the badges on a tag spell out. */
    private static List<String> badgeLabels(Component component) {
        return WynnPillGlyphs.findPills(component.getString()).stream()
                .map(pill -> pill.label().toLowerCase(Locale.ROOT))
                .toList();
    }



    /**
     * Wynncraft's layered badge: a corner-framed foreground layer, a back-advance,
     * then a shadow layer repeating the label, per {@link WynnPillGlyphs}.
     */
    private static String layeredBadge(String label) {
        StringBuilder badge = new StringBuilder().appendCodePoint(0xE060);
        for (int index = 0; index < label.length(); index++) {
            badge.appendCodePoint(0xCFFFF).appendCodePoint(0xE030 + (label.charAt(index) - 'a'));
        }
        badge.appendCodePoint(0xCFFFF).appendCodePoint(0xE062).appendCodePoint(0xCFF80);
        for (int index = 0; index < label.length(); index++) {
            badge.appendCodePoint(0xE000 + (label.charAt(index) - 'a'));
        }
        return badge.appendCodePoint(0xD0002).toString();
    }

    @Test
    void theRankIsFoundByAccountWhenTheRosterCarriesOne() {
        DiscordRankService service = serviceWith(profile(ROSTER_UUID.toString(), "ArcLeRetour"));

        RankPresentation rank =
                GuildRankNametagDecorator.rankFor(service, ROSTER_UUID, "ArcLeRetour");

        assertNotNull(rank);
        assertEquals("Sapling", rank.rank().label());
    }

    @Test
    void theRankIsStillFoundWhenTheRosterHasNoAccountId() {
        DiscordRankService service = serviceWith(profile(null, "ArcLeRetour"));

        RankPresentation rank =
                GuildRankNametagDecorator.rankFor(service, ROSTER_UUID, "ArcLeRetour");

        assertNotNull(rank, "a member without a stored uuid still wears their rank");
        assertEquals("Sapling", rank.rank().label());
    }

    @Test
    void theAccountIdIsTriedBeforeTheName() {
        DiscordRankService service = serviceWith(
                profile(ROSTER_UUID.toString(), "ArcLeRetour"), profile(null, "Impostor"));

        RankPresentation rank = GuildRankNametagDecorator.rankFor(service, ROSTER_UUID, "Impostor");

        assertNotNull(rank);
        assertEquals("Sapling", rank.rank().label());
    }

    @Test
    void aKnownDifferentAccountCannotBeSelectedByName() {
        DiscordRankService service = serviceWith(profile(ROSTER_UUID.toString(), "ArcLeRetour"));
        assertNull(GuildRankNametagDecorator.rankFor(service, UUID.randomUUID(), "ArcLeRetour"));
    }

    @Test
    void nobodyOnTheRosterWearsNothing() {
        DiscordRankService service = serviceWith(profile(ROSTER_UUID.toString(), "ArcLeRetour"));

        assertNull(GuildRankNametagDecorator.rankFor(service, UUID.randomUUID(), "Stranger"));
    }

    private static RankProfilesResponse.Profile profile(String uuid, String username) {
        return new RankProfilesResponse.Profile(
                new RankProfilesResponse.MinecraftIdentity(uuid, username),
                List.of("rank.sapling"),
                List.of());
    }

    private static DiscordRankService serviceWith(RankProfilesResponse.Profile... profiles) {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                new RankProfilesResponse.Catalog(
                        List.of(new RankProfilesResponse.RoleDefinition(
                                "rank.sapling",
                                "Sapling",
                                "progression_rank",
                                null,
                                null,
                                88,
                                new RankProfilesResponse.RoleColors("#4CB4FA", null, null))),
                        List.of(),
                        List.of()),
                List.of(profiles));
        return DiscordRankService.withIndex(DiscordRankService.parseProfiles(response));
    }

    /**
     * The submission a nametag is decorated at carries no entity, only a component, so
     * the player is named separately for the length of their render. A tag submitted
     * outside that, by a mob or a hologram, belongs to nobody and is left alone even
     * when it carries a member's name.
     */
    @Test
    void decoratesNothingWithoutAKnownOwner() {
        Setting.BooleanSetting previous = SeqClient.showNametagRanksSetting;
        try {
            SeqClient.showNametagRanksSetting =
                    new Setting.BooleanSetting("show_nametag_ranks", "nametags", true);
            Component nameTag = Component.literal(CHAMPION_BADGE + " ArcLeRetour");

            assertSame(nameTag, GuildRankNametagDecorator.decorate(null, nameTag));
            assertSame(nameTag, GuildRankNametagDecorator.decorate(UUID.randomUUID(), nameTag));
        } finally {
            SeqClient.showNametagRanksSetting = previous;
        }
    }
}
