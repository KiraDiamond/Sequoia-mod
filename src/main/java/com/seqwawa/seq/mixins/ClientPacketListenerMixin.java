package com.seqwawa.seq.mixins;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import com.seqwawa.seq.managers.ChatManager;
import com.seqwawa.seq.managers.GuildBankTracker;
import com.seqwawa.seq.managers.GuildStorageTracker;
import com.seqwawa.seq.managers.MinecraftCharacterClassDetector;
import com.seqwawa.seq.managers.MinecraftWarTowerTracker;
import com.seqwawa.seq.managers.RaidTracker;
import com.seqwawa.seq.raids.tna.TnaLineupHelper;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts system chat packets at the earliest possible point, before
 * Wynntils (or Fabric's message API) can cancel/reformat them.
 * <p>
 * This ensures guild messages and raid completions — including multiline ones
 * that Wynntils rewrites — are always observed by {@link ChatManager} and
 * {@link RaidTracker}.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(
            method = "handleOpenScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void seq$onHandleOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        com.seqwawa.seq.managers.WorldEventLootTracker.getInstance()
                .opened(packet.getContainerId(), packet.getTitle(), packet.getType());
        if (MinecraftCharacterClassDetector.getInstance()
                .onCharacterInfoOpened(packet.getContainerId(), packet.getTitle())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerContent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void seq$onHandleContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        com.seqwawa.seq.managers.WorldEventLootTracker.getInstance().contents(packet.containerId(), packet.items());
        if (MinecraftCharacterClassDetector.getInstance()
                .onCharacterInfoContents(packet.containerId(), packet.items())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerClose",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void seq$onHandleContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        com.seqwawa.seq.managers.WorldEventLootTracker.getInstance().closed(packet.getContainerId());
        if (MinecraftCharacterClassDetector.getInstance().onCharacterInfoClosed(packet.getContainerId())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerSetSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void seq$onEventRewardSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        com.seqwawa.seq.managers.WorldEventLootTracker.getInstance()
                .slot(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(
            method = "handleBossUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void seq$onHandleBossUpdate(ClientboundBossEventPacket packet, CallbackInfo ci) {
        MinecraftWarTowerTracker.getInstance().onBossEvent(packet);
    }

    @Inject(
            method = "handleSystemChat",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void seq$onHandleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (packet.overlay()) return;

        Component content = packet.content();
        com.seqwawa.seq.managers.WorldEventLootTracker.getInstance().onChat(content);
        ChatManager.onSystemChat(content);
        if (SeqClient.getWarTerritoryQueueManager() != null) {
            SeqClient.getWarTerritoryQueueManager().onSystemChat(content);
        }
        RaidTracker.onSystemChat(content);
        GuildStorageTracker.getInstance().onSystemChat(content);
        GuildBankTracker.getInstance().onSystemChat(content);
        if (SeqClient.getGuildRewardAutomationManager() != null) {
            SeqClient.getGuildRewardAutomationManager().onSystemChat(content);
        }
        if (SeqClient.getGuildWarTracker() != null) {
            SeqClient.getGuildWarTracker().onSystemChat(content);
        } else {
            String cleaned = PacketTextNormalizer.normalizeForParsing(content.getString());
            if (cleaned.contains("Territory Captured")) {
                SeqClient.LOGGER.warn(
                        "[GuildWarTracker] Ignoring completion chat because no guild war tracker is available");
            }
        }
        if (SeqClient.getWynnPartySyncManager() != null) {
            SeqClient.getWynnPartySyncManager().onSystemChat(content);
        }
    }

    @Inject(
            method = "setTitleText",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void seq$onSetTitleText(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        RaidTracker.onTitle(packet.text());
        TnaLineupHelper.onTitle(packet.text());
    }
}
