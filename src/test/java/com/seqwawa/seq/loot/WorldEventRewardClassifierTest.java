package com.seqwawa.seq.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldEventRewardClassifierTest {
    @Test
    void recognizesAllFiveRuneNamesWithoutDependingOnTheGraphicalBadge() {
        for (String type : List.of("Nii", "Uth", "Tol", "Az", "Ek")) {
            assertEquals("rune", WorldEventRewardClassifier.category(type + " Rune", List.of(
                    "Use this item to enter Raids", "or craft a Corrupted Dungeon", "key"), false));
            assertEquals("rune", WorldEventRewardClassifier.category(type.toLowerCase() + " rune", List.of(), true));
        }
        assertEquals("other", WorldEventRewardClassifier.category("Unknown Rune", List.of(), false));
        assertEquals("other", WorldEventRewardClassifier.category("Uth Runes", List.of(), false));
        assertEquals("other", WorldEventRewardClassifier.category("Fragment", List.of("Find an Uth Rune"), false));
    }

    @Test
    void recognizesScreenshotItemsAndOtherPairedIdentifiedTooltipsBeforeUnidentifiedFlavorText() {
        assertEquals("event_item", WorldEventRewardClassifier.category("Third Clarion", List.of(
                "Right-Click to use", "Effect:", "- Effect: Fire Resistance", "- Duration: 1 minute",
                "- Cooldown: 3 minutes", "Fabled Utility Trinket"), false));
        assertEquals("event_item", WorldEventRewardClassifier.category("Corrupted Cache", List.of(
                "Right-Click to open", "When opened, grants 1 of 5", "exclusive Mythics", "Mythic Item", "Untradable"), false));
        assertEquals("event_item", WorldEventRewardClassifier.category("Third Clarion", List.of(), true));
        assertEquals("event_item", WorldEventRewardClassifier.category("Corrupted Cache", List.of(), false));
        assertEquals("event_item", WorldEventRewardClassifier.category("Another Horn", List.of(
                "Fabled Utility Trinket", "Right-Click to use", "An unidentified source forged this horn."), false));
        assertEquals("event_item", WorldEventRewardClassifier.category("Another Cache", List.of(
                " mythic item ", " right-click to open "), false));
        assertEquals("rune", WorldEventRewardClassifier.category("Nii Rune", List.of("Unidentified flavor text"), false));
    }

    @Test
    void requiresPairedCompleteTypeAndActionLinesAndKeepsGeneralRewardPrecedence() {
        for (List<String> lore : List.of(
                List.of("Fabled Utility Trinket"),
                List.of("Mythic Item"),
                List.of("Right-Click to open"),
                List.of("Fabled Utility Trinket", "Right-Click to open"),
                List.of("Mythic Item", "Right-Click to use"),
                List.of("This Fabled Utility Trinket is ancient", "Right-Click to use"),
                List.of("Mythic Item", "You can Right-Click to open this"))) {
            assertEquals("other", WorldEventRewardClassifier.category("Unknown Reward", lore, false), lore.toString());
        }
        List<String> lore = List.of("Fabled Utility Trinket", "Right-Click to use", "96-100 Level Range");
        assertEquals("emerald", WorldEventRewardClassifier.category("Emerald", lore, false));
        assertEquals("unidentified_box", WorldEventRewardClassifier.category("Unidentified Boots", lore, true));
        assertEquals("potion", WorldEventRewardClassifier.category("Potion of Healing", lore, true));
        assertEquals("ingredient", WorldEventRewardClassifier.category("Third Clarion", List.of("Crafting Ingredient"), false));
    }

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
