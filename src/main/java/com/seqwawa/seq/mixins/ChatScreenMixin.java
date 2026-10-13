package com.seqwawa.seq.mixins;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.images.ImagePreviews;
import com.seqwawa.seq.utils.SequoiaProfileLinks;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns shift-clicking a player's name in chat into a link to their Sequoia
 * profile.
 * <p>
 * Shift-click is vanilla's "insert this name into the chat box" gesture, and the
 * name it inserts is exactly the player's real username, the same insertion this
 * mod already reads to resolve ranks. Hooking the gesture therefore works for
 * every player name Wynncraft renders, guild chat and bridged lines alike, with
 * no need to tag components beforehand.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "handleComponentClicked(Lnet/minecraft/network/chat/Style;Z)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void seq$openSequoiaProfile(
            Style style, boolean insertionMode, CallbackInfoReturnable<Boolean> callback) {
        if (ImagePreviews.click(style, insertionMode, this)) {
            callback.setReturnValue(true);
            return;
        }
        if (!insertionMode || style == null || !isEnabled()) {
            return;
        }

        URI profile = SequoiaProfileLinks.playerCard(style.getInsertion());
        if (profile == null) {
            // Not a player name: leave the vanilla insertion alone.
            return;
        }
        callback.setReturnValue(clickUrlAction(Minecraft.getInstance(), this, profile));
    }

    private static boolean isEnabled() {
        Setting.BooleanSetting setting = SeqClient.getProfileOnShiftClickSetting();
        return setting != null && setting.getValue();
    }
}
