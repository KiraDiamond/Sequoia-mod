package com.seqwawa.seq.mixins;

import com.seqwawa.seq.images.ImagePreviews;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
abstract class GuiGraphicsMixin {
    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
    private void seq$previewImage(Font font, Style style, int x, int y, CallbackInfo info) {
        if (ImagePreviews.hover((GuiGraphics) (Object) this, style, x, y)) info.cancel();
    }
}
