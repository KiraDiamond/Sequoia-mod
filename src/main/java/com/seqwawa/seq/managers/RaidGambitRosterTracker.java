package com.seqwawa.seq.managers;

import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/** Tracks the gambits shown for every player in Wynncraft's raid-start menu. */
public final class RaidGambitRosterTracker {
    private static final List<Integer> PLAYER_SLOTS = List.of(18, 19, 20, 21);
    private static final int MAX_GAMBIT_COUNT = 4;
    private static final String GAMBIT_HEADER = "Enabled Gambits:";
    private static final Pattern GAMBIT_NAME_PATTERN = Pattern.compile("(?i)^-\\s+.+\\sGambit$");

    private static AbstractContainerMenu activeMenu;
    private static volatile Map<String, Integer> latestCounts = Map.of();

    private RaidGambitRosterTracker() {}

    public static void observe(AbstractContainerMenu menu, Component screenTitle) {
        if (menu == null || !RaidStartScreenDetector.isRaidStartScreen(screenTitle)) {
            return;
        }
        if (activeMenu != menu) {
            activeMenu = menu;
            latestCounts = Map.of();
        }

        Map<String, Integer> observed = new LinkedHashMap<>();
        for (int slotIndex : PLAYER_SLOTS) {
            if (slotIndex >= menu.slots.size()) {
                continue;
            }
            parsePlayerSlot(menu.slots.get(slotIndex).getItem())
                    .ifPresent(player -> observed.put(player.username(), player.gambitCount()));
        }
        latestCounts = Map.copyOf(observed);
    }

    public static Map<String, Integer> snapshotForParty(List<String> partyMembers) {
        return filterForParty(latestCounts, partyMembers);
    }

    static Map<String, Integer> filterForParty(
            Map<String, Integer> observedCounts, List<String> partyMembers) {
        if (partyMembers == null || partyMembers.isEmpty() || observedCounts == null || observedCounts.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> byNormalizedName = new LinkedHashMap<>();
        observedCounts.forEach((username, count) -> byNormalizedName.put(username.toLowerCase(Locale.ROOT), count));

        Map<String, Integer> matched = new LinkedHashMap<>();
        for (String partyMember : partyMembers) {
            if (partyMember == null) {
                continue;
            }
            Integer count = byNormalizedName.get(partyMember.toLowerCase(Locale.ROOT));
            if (count != null) {
                matched.put(partyMember, count);
            }
        }
        return Map.copyOf(matched);
    }

    public static void reset() {
        activeMenu = null;
        latestCounts = Map.of();
    }

    static Optional<PlayerGambitObservation> parsePlayerSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
            return Optional.empty();
        }
        String username = resolveUsername(stack);
        if (username == null) {
            return Optional.empty();
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        int gambitCount = parseGambitCount(lore == null ? List.of() : lore.lines());
        return gambitCount < 0
                ? Optional.empty()
                : Optional.of(new PlayerGambitObservation(username, gambitCount));
    }

    static int parseGambitCount(List<Component> loreLines) {
        boolean insideGambits = false;
        int count = 0;
        for (Component line : loreLines) {
            String text = PacketTextNormalizer.normalizeForParsing(line.getString());
            if (GAMBIT_HEADER.equalsIgnoreCase(text)) {
                insideGambits = true;
                continue;
            }
            if (insideGambits && GAMBIT_NAME_PATTERN.matcher(text).matches()) {
                count++;
                if (count > MAX_GAMBIT_COUNT) {
                    return -1;
                }
            }
        }
        return count;
    }

    private static String resolveUsername(ItemStack stack) {
        Component hoverName = stack.getHoverName();
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile != null) {
            return PacketNameResolver.profile(hoverName.getString(), profile.name().orElse(null),
                    profile.partialProfile().id(), "raid.gambit").username();
        }

        var resolver = PacketNameResolver.from(hoverName);
        return resolver.resolve(0, resolver.text().length(), PacketNameResolver.Policy.CHAT, "raid.gambit").username();
    }

    record PlayerGambitObservation(String username, int gambitCount) {}
}
