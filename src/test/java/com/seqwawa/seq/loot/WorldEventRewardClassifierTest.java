package com.seqwawa.seq.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldEventRewardClassifierTest {
    @Test
    void recognizesModernIngredientWithGlyphBadgeAndCraftingRecipeLore() {
        List<String> lore = List.of("", "\uDB00\uDC26\uE000\uE000\uE000\uDB00\uDC02", "",
                "14 Crafting Level", "Can be used in recipes for", "Jeweling", "", "Durability -66");
        List<String> normalized = lore.stream().map(PacketTextNormalizer::normalizeForParsing).toList();
        assertEquals("ingredient", WorldEventRewardClassifier.category("Glowing Tree Sap", normalized, false));
        assertEquals("ingredient", WorldEventRewardClassifier.category("Glowing Tree Sap",
                List.of("Crafting Level", "Can be used in recipes for", "Jeweling"), true));
        assertEquals("ingredient", WorldEventRewardClassifier.category("Glowing Tree Sap",
                List.of(" 14 crafting level ", " can be used in recipes for "), false));
    }

    @Test
    void requiresBothExactModernIngredientLoreLinesAndPreservesKnownCategories() {
        for (List<String> lore : List.of(
                List.of("14 Crafting Level"),
                List.of("Can be used in recipes for"),
                List.of("This ingredient can be used in crafting"),
                List.of("Requires 14 Crafting Level", "Can be used in recipes for"),
                List.of("14 Crafting Level", "Cannot be used in recipes for"),
                List.of("14 Crafting Level Can be used in recipes for"))) {
            assertEquals("other", WorldEventRewardClassifier.category("Unknown Reward", lore, false), lore.toString());
        }
        List<String> lore = List.of("14 Crafting Level", "Can be used in recipes for", "96-100 Level Range");
        assertEquals("emerald", WorldEventRewardClassifier.category("Emerald", lore, false));
        assertEquals("unidentified_box", WorldEventRewardClassifier.category("Unidentified Boots", lore, true));
    }

    @Test
    void distinguishesNamedUnidentifiedEquipmentFromGenericPotionBackedBoxes() {
        assertEquals("unidentified_item", WorldEventRewardClassifier.category(
                "Event Boots", List.of("Unidentified Legendary Item"), false));
        assertEquals("unidentified_item", WorldEventRewardClassifier.category(
                "Event Boots", List.of(), false, "󏀀§f\uE008󐀂§dEvent Boots󏀀"));
        assertEquals("unidentified_box", WorldEventRewardClassifier.category(
                "Unidentified Boots", List.of("", "", "96-100 Level Range"), true));
        assertEquals("other", WorldEventRewardClassifier.category("Unidentified Boots", List.of(), true));
    }

    @Test
    void recognizesBothRewardGroupsAndRetainsUnknownItems() {
        assertEquals("ingredient", WorldEventRewardClassifier.category("Rare Feather", List.of("Crafting Ingredient"), false));
        assertEquals("liquid_emerald", WorldEventRewardClassifier.category("Liquid Emerald", List.of(), false));
        assertEquals("emerald", WorldEventRewardClassifier.category("Emerald", List.of(), false));
        assertEquals("emerald_block", WorldEventRewardClassifier.category("Emerald Block", List.of(), false));
        assertEquals("potion", WorldEventRewardClassifier.category("Potion of Healing", List.of(), true));
        assertEquals("other", WorldEventRewardClassifier.category("New Reward", List.of("Unknown lore"), false));
    }
}
