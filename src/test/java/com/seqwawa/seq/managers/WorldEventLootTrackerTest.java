package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldEventLootTrackerTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsNativeTextDisplayAndLegacyCustomNameLabels() {
        var display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, null);
        display.setText(Component.literal("§#ebf7ffffTest Event"));
        assertEquals(List.of("Test Event"), WorldEventLootTracker.eventLabels(display));
        var stand = new ArmorStand(EntityType.ARMOR_STAND, null);
        stand.setCustomName(Component.literal("§fLegacy Event"));
        assertEquals(List.of("Legacy Event"), WorldEventLootTracker.eventLabels(stand));
        display.setText(Component.literal("§#ebf7ffffTest Event\n§#aeb8bfffStarts in 10s"));
        assertEquals(List.of("Test Event", "Starts in 10s"), WorldEventLootTracker.eventLabels(display));
    }

    @Test
    void normalizesActualEventMessagesAndPreservesOrdinaryText() {
        assertEquals("Event Completed", WorldEventLootTracker.clean("§#00bdbfff\ue001 §fEvent Completed"));
        assertEquals("You are now within the event radius",
                WorldEventLootTracker.clean("§#00bdbfff\ue00d\ue002 You are now within the event radius"));
        assertEquals("Player: Event Completed", WorldEventLootTracker.clean("Player: Event Completed"));
    }
}
