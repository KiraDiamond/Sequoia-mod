package com.seqwawa.seq.mixins;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.managers.RaidGambitRosterTracker;
import com.seqwawa.seq.ui.GuildStorageShortcutOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {
    @Shadow
    protected int imageWidth;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    @Final
    protected T menu;

    @Inject(method = "render", at = @At("TAIL"))
    private void seq$renderGuildStorageShortcut(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuildStorageShortcutOverlay.render(graphics, menu, leftPos, topPos, imageWidth, mouseX, mouseY);
    }

    @Inject(method = "renderContents", at = @At("TAIL"))
    private void seq$observeRaidGambitRoster(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        RaidGambitRosterTracker.observe(menu, screen.getTitle());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void seq$clickGuildStorageShortcut(
            MouseButtonEvent event, boolean outsideScreen, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0) {
            return;
        }

        if (GuildStorageShortcutOverlay.mouseClicked(menu, leftPos, topPos, imageWidth, event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "slotClicked", at = @At("HEAD"))
    private void seq$clickWarQueueButton(Slot slot, int i, int j, ClickType clickType, CallbackInfo ci) {
        if (clickType != ClickType.PICKUP && clickType != ClickType.SWAP) {
            return;
        }

        if (SeqClient.getGuildWarTracker() != null && slot != null && Minecraft.getInstance().screen != null) {
            SeqClient.getGuildWarTracker().onSlotClick(Minecraft.getInstance().screen.getTitle().getString(), slot.getItem());
        }
    }
}
