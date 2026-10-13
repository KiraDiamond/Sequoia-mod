package com.seqwawa.seq.mixins;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface GuiTitleAccessor {
    @Accessor("title")
    Component seq$getTitle();

    @Accessor("subtitle")
    Component seq$getSubtitle();

    @Accessor("titleTime")
    int seq$getTitleTime();
}
