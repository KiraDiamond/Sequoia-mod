package com.seqwawa.seq.managers;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

final class IdentityTestSupport {
    static void remember(String alias, String username) {
        var resolver = PacketNameResolver.from(Component.literal(alias).withStyle(Style.EMPTY.withInsertion(username)));
        resolver.observe(0, resolver.text().length(), PacketNameResolver.Policy.ALIAS, "test.player");
    }
    static String resolve(String alias) {
        return NicknameResolverCache.lookup(alias, false, "test.player", 0, alias.length(), NicknameResolverCache.generation()).username();
    }
}
