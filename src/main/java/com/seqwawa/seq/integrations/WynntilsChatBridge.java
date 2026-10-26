package com.seqwawa.seq.integrations;

import com.wynntils.core.components.Models;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.features.chat.RevealNicknamesFeature;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.handlers.chat.type.RecipientType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import com.wynntils.core.events.MixinHelper;
import com.wynntils.mc.event.AddGuiMessageLineEvent;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.GuiMessage;

/** Loaded reflectively only after Wynntils is detected. */
public final class WynntilsChatBridge implements WynntilsChatAccess.Bridge {
    @Override
    public CompletableFuture<String> lookupGuildTag(String username) {
        return Models.Player.getPlayer(username).thenApply(player -> player == null ? null
                : player.guildInfo().map(guild -> guild.guildPrefix()).orElse(""));
    }

    @Override
    public Component revealNickname(Component name, String username) {
        var feature = Managers.Feature.getFeatureInstance(RevealNicknamesFeature.class);
        if (feature == null || !feature.isEnabled()) return name;
        // This is already an attributed backend sender, not an incoming chat event.
        // Invoke only the reveal feature, before gradients split the nickname into characters.
        var source = name.copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("§f" + name.getString() + "§7's real name is §f" + username))));
        var original = StyledText.fromComponent(source);
        var edit = new ChatMessageEvent.Edit(original, RecipientType.GUILD);
        feature.onPlayerChat(edit);
        return edit.getMessage().equals(original) ? name : edit.getMessage().getComponent();
    }

    @Override
    public void postAddedLine(GuiMessage message, GuiMessage.Line line) {
        MixinHelper.post(new AddGuiMessageLineEvent(message, line));
    }
}
