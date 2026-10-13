package com.seqwawa.seq.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.seqwawa.seq.images.ImagePreviews;
import com.seqwawa.seq.integrations.WynntilsChatAccess;
import com.seqwawa.seq.managers.DiscordRankChatDecorator;
import com.seqwawa.seq.managers.PrivateMessageGuildTagDecorator;
import com.seqwawa.seq.managers.PrivateMessageGuildTagView;
import com.seqwawa.seq.managers.WorldSwitchChatDecorator;
import com.seqwawa.seq.utils.ChatBridgeLineWrapping;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies Sequoia Discord rank presentation and world-name links just before a chat
 * line is queued for display. Guild chat can replace its rank badge, party chat only
 * receives the linked member's username colour, and supported world names become
 * clickable after that rank decoration is complete.
 * <p>
 * This is the last hop every chat line takes, since {@code addMessage(Component)}
 * delegates here, so it also covers messages Wynntils has already reformatted
 * (timestamps, nickname rendering), unlike the packet-level hook used by
 * {@link com.seqwawa.seq.managers.ChatManager}.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void seq$resetChatSequences(boolean clearSentMessages, CallbackInfo ci) {
        DiscordRankChatDecorator.resetChatSequences();
    }

    @Unique
    private final Runnable seq$refreshGuildTags = this::seq$updateGuildTagLines;

    @Unique
    private final PrivateMessageGuildTagView seq$guildTagView = new PrivateMessageGuildTagView(
            () -> PrivateMessageGuildTagDecorator.queueRefresh(seq$refreshGuildTags));

    @Unique
    private int seq$chatHistoryLimit = 100;

    @Shadow
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    private int chatScrollbarPos;

    @Shadow
    public int getLinesPerPage() {
        throw new AssertionError();
    }

    @ModifyExpressionValue(method = "addMessageToDisplayQueue", at = @At(value = "CONSTANT", args = "intValue=100"))
    private int seq$captureHistoryLimit(int limit) {
        seq$chatHistoryLimit = limit;
        return limit;
    }

    @Unique
    private void seq$updateGuildTagLines() {
        chatScrollbarPos = seq$guildTagView.refresh(
                trimmedMessages, chatScrollbarPos, getLinesPerPage(), seq$chatHistoryLimit,
                WynntilsChatAccess::postAddedLine);
    }

    /**
     * Rank decoration runs first: it rebuilds guild lines around the pill it
     * inserts, and linking is a pure restyle that survives being applied to the
     * result. World links are deliberately not limited to guild chat, so a world
     * named on the Discord bridge or in a shared bomb list is clickable too.
     */
    @ModifyVariable(
            method =
                    "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1)
    private Component seq$decorateChatLine(Component message) {
        Component decorated = DiscordRankChatDecorator.decorateGuildChat(message);
        Component linked = WorldSwitchChatDecorator.decorate(decorated);
        DiscordRankChatDecorator.retainBridgeRail(decorated, linked);
        return ImagePreviews.decorate(linked);
    }

    /**
     * Minecraft creates visual chat lines only after the component enters its display
     * queue. Reserve room for the bridge rail at that point, then put the rail on every
     * automatically wrapped line after the sender line.
     */
    @Redirect(
            method = "addMessageToDisplayQueue",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
    private List<FormattedCharSequence> seq$wrapBridgeContinuations(
            GuiMessage message, Font font, int maxWidth) {
        if (PrivateMessageGuildTagDecorator.isPrivateMessage(message.content())) {
            return seq$guildTagView.wrap(message, PrivateMessageGuildTagDecorator::decorate,
                    tagged -> new GuiMessage(message.addedTime(), tagged, message.signature(), message.tag())
                            .splitLines(font, maxWidth));
        }
        List<FormattedCharSequence> initialLines = message.splitLines(font, maxWidth);
        Component continuationPrefix = DiscordRankChatDecorator.bridgeContinuationPrefixFor(message.content());
        if (continuationPrefix == null) {
            return initialLines;
        }

        return ChatBridgeLineWrapping.wrapColoredBridgeMessage(
                initialLines, message, font, maxWidth, continuationPrefix);
    }
}
