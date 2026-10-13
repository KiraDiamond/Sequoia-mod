package com.seqwawa.seq.managers;

import com.seqwawa.seq.mixins.GuiTitleAccessor;
import com.seqwawa.seq.network.WynncraftServerPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Detects the server queue from the current title, without retaining historical packet text. */
public final class ServerQueueDetector {
    private ServerQueueDetector() {}

    /** Must be called on the client thread, which owns the GUI title state. */
    public static boolean isInQueue() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean connectedToMainServer = minecraft.getConnection() != null
                && minecraft.player != null && minecraft.level != null
                && WynncraftServerPolicy.isCurrentServerAllowed();
        GuiTitleAccessor gui = (GuiTitleAccessor) minecraft.gui;
        return matchesQueueTitle(
                gui.seq$getTitle(), gui.seq$getSubtitle(), gui.seq$getTitleTime(), connectedToMainServer);
    }

    static boolean matchesQueueTitle(
            Component title, Component subtitle, int titleTime, boolean connectedToMainServer) {
        return connectedToMainServer && titleTime > 0 && title != null && subtitle != null
                && title.getString().contains("Queueing for")
                && subtitle.getString().contains("You are in position");
    }
}
