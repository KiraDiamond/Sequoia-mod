package com.seqwawa.seq.managers;

import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.ComponentTextEditor;
import com.seqwawa.seq.utils.RankGradientAnimation;
import com.seqwawa.seq.utils.WynnPillGlyphs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

/**
 * Swaps the Wynncraft rank badge on a player's in-world nametag for the member's
 * Sequoia Discord rank, the same substitution {@link DiscordRankChatDecorator}
 * makes in guild chat and on the Discord bridge. Players with no linked Sequoia
 * rank keep the nametag Wynncraft sent, untouched.
 * <p>
 * The rewrite happens while an {@code AvatarRenderer} submits a player's nametag,
 * after the render state has been extracted. This keeps changes made to that state
 * by other mods while retaining the UUID of the player it belongs to.
 * <p>
 * {@link #rememberRenderedPlayer} records the names that this particular player's
 * tag may display. Decoration is then keyed by the same UUID, so an ordinary
 * hologram, a mob, or another account using the same text cannot pick up the rank.
 * <p>
 * Everything here runs on the render thread.
 */
public final class GuildRankNametagDecorator {

    /** Bounds on the two caches, ample for the players one client can see at once. */
    private static final int MAX_REMEMBERED_PLAYERS = 256;
    private static final int MAX_CACHED_NAMETAGS = 256;

    private static final int MIN_NAME_LENGTH = 3;
    private static final int MAX_NAME_LENGTH = 16;

    private static final FontDescription WYNNTILS_NAMETAG_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("wynntils", "nametag"));

    /** What was last published for a player, so an unchanged frame costs one lookup. */
    private static final Map<UUID, Registration> REGISTRATIONS = boundedMap(MAX_REMEMBERED_PLAYERS);

    /**
     * Decoration results keyed by the component handed to the renderer, including
     * the ones left alone. A nametag is submitted every frame from a component that
     * upstream caches, so without this the rank would be rebuilt — and its animated
     * colours re-registered — sixty times a second per player.
     */
    private static final Map<DecorationKey, Decoration> DECORATED_NAMETAGS = decorationCache();

    private GuildRankNametagDecorator() {}

    /**
     * Publishes the identity of a player being rendered this frame, keyed by every
     * name their nametag can show: their account name and whatever Wynncraft
     * currently displays, which differ while they are nicknamed.
     *
     * @param nameTag the nametag Wynncraft supplied, before any mod has touched it
     */
    public static void rememberRenderedPlayer(UUID uuid, String username, Component nameTag) {
        if (uuid == null || !isEnabled()) {
            return;
        }

        RankPresentation rank = rankFor(uuid, username);
        Member member = rank == null ? null : new Member(username, rank);
        Registration replacement = new Registration(nameTag, member, registeredNames(username, nameTag));
        Registration previous = REGISTRATIONS.get(uuid);
        if (replacement.equals(previous)) {
            return;
        }

        // Replacing the registration replaces its complete alias set. No obsolete
        // nickname remains available to this UUID after the displayed tag changes.
        REGISTRATIONS.put(uuid, replacement);
        forgetDecorations(uuid);
    }

    /**
     * The nametag to draw in place of {@code nameTag}: the same tag with its rank
     * badge replaced by the speaker's Sequoia rank, or {@code nameTag} itself when
     * it belongs to nobody the roster knows.
     */
    public static Component decorate(UUID uuid, Component nameTag) {
        if (uuid == null || nameTag == null || !isEnabled()) {
            return nameTag;
        }

        Registration registration = REGISTRATIONS.get(uuid);
        if (registration == null || registration.member() == null) {
            return nameTag;
        }

        DecorationKey key = new DecorationKey(uuid, nameTag);
        Decoration cached = DECORATED_NAMETAGS.get(key);
        if (cached != null) {
            return cached.component();
        }

        Decoration decorated;
        try {
            decorated = decorate(nameTag, registration::memberFor);
        } catch (RuntimeException exception) {
            SeqClient.LOGGER.debug("[DiscordRanks] Failed to decorate a nametag.", exception);
            decorated = Decoration.unchanged(nameTag);
        }
        DECORATED_NAMETAGS.put(key, decorated);
        return decorated.component();
    }

    /**
     * Decoration core, parameterised on the identity lookup so it stays unit-testable.
     * <p>
     * The colours are pinned rather than merely registered: a nametag stands on screen
     * for as long as its owner is in sight, far longer than the chat line this registry
     * was built for, and a stop evicted underneath it would drop that one glyph out of
     * step with the rest of the rank.
     */
    static Decoration decorate(Component nameTag, Function<String, Member> members) {
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flattenFormatted(nameTag);
        String text = ComponentTextEditor.textOf(fragments);
        DisplayedName name = displayedName(text, members);
        if (name == null) {
            return Decoration.unchanged(nameTag);
        }

        Member member = name.member();
        String label = PrincessRankEasterEgg.pillLabel(member.rank().pillLabel(), member.username());
        Badge badge = badgeBefore(fragments, text, name.start());
        if (alreadyDecorated(text, name.start(), label, badge)) {
            return Decoration.unchanged(nameTag);
        }

        RankGradientAnimation.Pinned<Component> pinned = RankGradientAnimation.pin(
                () -> rewrite(fragments, name, badge, label, badgeColor(fragments, badge)));
        return pinned.value() == null
                ? Decoration.unchanged(nameTag)
                : new Decoration(pinned.value(), pinned.colors());
    }

    private static Component rewrite(
            List<ComponentTextEditor.Fragment> fragments,
            DisplayedName name,
            Badge badge,
            String label,
            TextColor badgeColor) {
        RankPresentation rank = name.member().rank();
        List<ComponentTextEditor.Fragment> coloured = paintName(fragments, name, rank);
        MutableComponent replacement =
                Component.empty()
                .append(NotificationAccessor.wynnPill(
                        label,
                        DiscordRankChatDecorator.rampFor(rank),
                        DiscordRankChatDecorator.roleRampFor(rank),
                        DiscordRankChatDecorator.PILL_LABEL_COLOR,
                        null,
                        badgeColor))
                .append(Component.literal(" "));

        if (badge == null) {
            return ComponentTextEditor.toComponent(
                    ComponentTextEditor.insertAt(coloured, name.start(), replacement));
        }
        return ComponentTextEditor.replaceRange(coloured, badge.start(), badge.endExclusive(), replacement);
    }

    /**
     * Whether the tag already carries this rank: as the label written in front of the
     * name, or as a badge spelling it. A nametag is decorated every frame from the tag
     * its renderer holds, and a mod may hand back one this already rewrote.
     */
    private static boolean alreadyDecorated(String text, int nameStart, String label, Badge badge) {
        if (label.equalsIgnoreCase(badgeLabel(text, badge))) {
            return true;
        }
        String before = text.substring(0, nameStart).stripTrailing();
        return before.length() >= label.length()
                && before.regionMatches(true, before.length() - label.length(), label, 0, label.length());
    }

    /**
     * The colour Wynncraft drew the badge in, which the rank returns to when member
     * colouring is switched off. Without it the rank would fall back to the plain
     * white a nametag is drawn in, rather than to the rank colour it replaced.
     */
    private static TextColor badgeColor(List<ComponentTextEditor.Fragment> fragments, Badge badge) {
        if (badge == null) {
            return null;
        }
        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            cursor += fragment.text().length();
            if (cursor > badge.start()) {
                return fragment.style().getColor();
            }
        }
        return null;
    }

    /**
     * The first name on the tag that belongs to a player being rendered, and where it
     * starts. Wynncraft's badge glyphs are private-use characters, so they never form
     * one of these tokens and the name is always found past the badge.
     */
    static DisplayedName displayedName(String text, Function<String, Member> members) {
        for (Name name : names(text)) {
            Member member = members.apply(normalize(name.value()));
            if (member != null) {
                return new DisplayedName(name.start(), name.start() + name.value().length(), member);
            }
        }
        return null;
    }

    /**
     * Paints the member's name in their rank colours, so the name and the rank in
     * front of it read as one label rather than as a Sequoia rank stuck on a
     * Wynncraft-coloured name.
     * <p>
     * A gradient has to be painted a code point at a time, since a component leaf
     * carries one colour; a solid rank keeps the name in one piece, which is a
     * nametag's usual shape and less for the font to lay out every frame.
     */
    private static List<ComponentTextEditor.Fragment> paintName(
            List<ComponentTextEditor.Fragment> fragments, DisplayedName name, RankPresentation rank) {
        ColorRamp displayRamp = DiscordRankChatDecorator.rampFor(rank);
        ColorRamp roleRamp = DiscordRankChatDecorator.roleRampFor(rank);
        if (!displayRamp.isGradient() && !roleRamp.isGradient()) {
            return ComponentTextEditor.restyleRange(
                    fragments,
                    name.start(),
                    name.endExclusive(),
                    style -> DiscordRankChatDecorator.withRegisteredColor(
                            style,
                            RankGradientAnimation.colorAt(
                                    displayRamp,
                                    roleRamp,
                                    0d,
                                    RankGradientAnimation.Target.USERNAME,
                                    style.getColor())));
        }
        return ComponentTextEditor.restyleRangeByPosition(
                fragments,
                name.start(),
                name.endExclusive(),
                (style, position) -> DiscordRankChatDecorator.withRegisteredColor(
                        style,
                        RankGradientAnimation.colorAt(
                                displayRamp,
                                roleRamp,
                                position,
                                RankGradientAnimation.Target.USERNAME,
                                style.getColor())));
    }

    /**
     * The rank badge standing in front of the name, as one span running up to the
     * name itself.
     * <p>
     * Taken as an unbroken block of glyph characters rather than as a single decoded
     * pill, because Wynncraft's newer badges are drawn in layers — a foreground pill,
     * a back-advance, and a shadow that repeats the label — held together by
     * supplementary-plane characters that break a badge into several runs. Replacing
     * one run leaves the other layers behind as a ghost of the old rank.
     * <p>
     * A real space ends the search. Wynntils' logo font also forms a boundary:
     * its private-use glyph is a decoration even when no account badge follows it,
     * or when the logo and badge have no separating space.
     */
    private static Badge badgeBefore(
            List<ComponentTextEditor.Fragment> fragments, String text, int nameStart) {
        int lowerBound = 0;
        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            if (cursor >= nameStart) {
                break;
            }
            cursor += fragment.text().length();
            if (WYNNTILS_NAMETAG_FONT.equals(fragment.style().getFont())) {
                lowerBound = Math.min(cursor, nameStart);
            }
        }

        int end = nameStart;
        while (end > lowerBound && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        int start = end;
        while (start > lowerBound) {
            int codePoint = text.codePointBefore(start);
            if (!isBadgeCharacter(codePoint)) {
                break;
            }
            start -= Character.charCount(codePoint);
        }
        // The spacing belongs to the badge: the replacement brings its own.
        return start == end ? null : new Badge(start, nameStart);
    }

    /**
     * Glyph characters as Wynncraft builds badges from: its private-use font, the
     * unassigned supplementary codepoints it uses to advance between layers, and the
     * zero-width padding that separates them.
     */
    private static boolean isBadgeCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.FORMAT;
    }

    /** The rank the badge spells out, or {@code null} when this build cannot read it. */
    private static String badgeLabel(String text, Badge badge) {
        if (badge == null) {
            return null;
        }
        // Decode only the badge span: an adjacent logo in another font must not
        // become part of this glyph run and hide an already-decorated rank.
        List<WynnPillGlyphs.Pill> pills =
                WynnPillGlyphs.findPills(text.substring(badge.start(), badge.endExclusive()));
        return pills.isEmpty() ? null : pills.getFirst().label();
    }

    /** Every name on {@code text} that could be a Minecraft account name. */
    static List<String> nameCandidates(String text) {
        return names(text).stream().map(Name::value).toList();
    }

    /**
     * Every account-name-shaped run of {@code text}, with where it starts. Wynncraft's
     * badge glyphs are private-use characters, so a badge never forms one of these and
     * the name is always found past it.
     */
    private static List<Name> names(String text) {
        List<Name> names = new ArrayList<>(2);
        int index = 0;
        while (index < text.length()) {
            if (!isNameCharacter(text.charAt(index))) {
                index++;
                continue;
            }

            int start = index;
            while (index < text.length() && isNameCharacter(text.charAt(index))) {
                index++;
            }
            String candidate = text.substring(start, index);
            if (isNameSized(candidate)) {
                names.add(new Name(candidate, start));
            }
        }
        return names;
    }

    private static boolean isNameCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    private static boolean isNameSized(String candidate) {
        return candidate.length() >= MIN_NAME_LENGTH && candidate.length() <= MAX_NAME_LENGTH;
    }

    /** All account-name-shaped aliases currently shown for one rendered player. */
    private static List<String> registeredNames(String username, Component nameTag) {
        List<String> names = new ArrayList<>(3);
        addRegisteredName(names, username);
        for (String candidate : nameCandidates(
                ComponentTextEditor.textOf(ComponentTextEditor.flattenFormatted(nameTag)))) {
            addRegisteredName(names, candidate);
        }
        return List.copyOf(names);
    }

    private static void addRegisteredName(List<String> names, String name) {
        String key = key(name);
        if (key != null && !names.contains(key)) {
            names.add(key);
        }
    }

    /** The map key a name is stored under, or {@code null} when it cannot be one. */
    private static String key(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return isNameSized(trimmed) ? normalize(trimmed) : null;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * The member's rank. The UUID is preferred because it cannot be spoofed by a
     * nickname and survives a rename, but the roster only carries one for members
     * whose profile records it, so the account name still has to answer for the rest.
     */
    private static RankPresentation rankFor(UUID uuid, String username) {
        return rankFor(DiscordRankService.getInstance(), uuid, username);
    }

    static RankPresentation rankFor(DiscordRankService service, UUID uuid, String username) {
        return service.presentationForMinecraftAccount(uuid, username);
    }

    private static boolean isEnabled() {
        Setting.BooleanSetting setting = SeqClient.getShowNametagRanksSetting();
        return setting != null && setting.getValue();
    }

    /**
     * The decoration cache, which hands a dropped decoration's colours back to the
     * animation registry: nothing else knows when a nametag has stopped being drawn.
     */
    private static Map<DecorationKey, Decoration> decorationCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<DecorationKey, Decoration> eldest) {
                if (size() <= MAX_CACHED_NAMETAGS) {
                    return false;
                }
                RankGradientAnimation.release(eldest.getValue().colors());
                return true;
            }
        });
    }

    private static void forgetDecorations(UUID uuid) {
        synchronized (DECORATED_NAMETAGS) {
            List<List<TextColor>> released = new ArrayList<>();
            DECORATED_NAMETAGS.entrySet().removeIf(entry -> {
                if (!entry.getKey().uuid().equals(uuid)) {
                    return false;
                }
                released.add(entry.getValue().colors());
                return true;
            });
            RankGradientAnimation.releaseAll(released);
        }
    }

    private static <K, V> Map<K, V> boundedMap(int maximumEntries) {
        // Insertion ordered on purpose: an access-ordered map mutates on a read, and
        // these are read from the render loop.
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maximumEntries;
            }
        });
    }

    /** A player whose nametag should carry a Sequoia rank. */
    record Member(String username, RankPresentation rank) {}

    /** A rewritten nametag and the pinned colours it is drawn with. */
    record Decoration(Component component, List<TextColor> colors) {

        /** A nametag that belongs to nobody known, cached so it is only examined once. */
        static Decoration unchanged(Component nameTag) {
            return new Decoration(nameTag, List.of());
        }
    }

    /** Where a known member's name sits on a nametag. */
    record DisplayedName(int start, int endExclusive, Member member) {}

    /** A run of text on a nametag that is shaped like a Minecraft account name. */
    private record Name(String value, int start) {}

    /** The span a rank badge occupies, up to and including the space after it. */
    record Badge(int start, int endExclusive) {}

    /** Cache identity: the same component text may legitimately belong to two players. */
    private record DecorationKey(UUID uuid, Component nameTag) {}

    /** What was last published for one UUID, including only that player's aliases. */
    private record Registration(Component nameTag, Member member, List<String> names) {
        private Member memberFor(String candidate) {
            return names.contains(normalize(candidate)) ? member : null;
        }
    }
}
