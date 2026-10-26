package com.seqwawa.seq.managers;

import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/** Parses successful rank assignments without mistaking a nickname for an account name. */
public final class GuildRankEventParser {
    private static final String RANK = "(Recruit|Recruiter|Captain|Strategist|Chief|Owner)";
    private static final Pattern MESSAGE = Pattern.compile(
            "^(?:\\[guild] )?([A-Za-z0-9_][A-Za-z0-9_ ]{0,63}?) has set "
                    + "([A-Za-z0-9_][A-Za-z0-9_ ]{0,63}?) guild rank from " + RANK + " to " + RANK + "[.!]?$",
            Pattern.CASE_INSENSITIVE);

    private GuildRankEventParser() {}

    public static Event parse(Component message, String localUsername) {
        if (message == null || ChatManager.parseGuildMessage(message) != null) return null;
        PacketNameResolver resolver = PacketNameResolver.from(message);
        String text = resolver.text().trim();
        int offset = resolver.text().indexOf(text);
        var matcher = MESSAGE.matcher(text);
        if (!matcher.matches()) return null;
        return new Event(identity(resolver, matcher.group(1), matcher.start(1) + offset, matcher.end(1) + offset, localUsername, "rank.actor"),
                identity(resolver, matcher.group(2), matcher.start(2) + offset, matcher.end(2) + offset, localUsername, "rank.target"), rank(matcher.group(3)), rank(matcher.group(4)), text);
    }

    private static Identity identity(PacketNameResolver resolver, String display, int start, int end,
                                     String localUsername, String role) {
        if ("you".equalsIgnoreCase(display) && (PacketNameResolver.validUsername(localUsername) != null))
            return new Identity(display, localUsername, "local_player");
        var result = resolver.resolve(start, end, PacketNameResolver.Policy.METADATA, role);
        return new Identity(display, result.username() == null ? null : result.username().toLowerCase(Locale.ROOT),
                result.username() != null ? "component" : "unresolved");
    }

    private static String rank(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1).toLowerCase(Locale.ROOT);
    }

    public record Identity(String displayName, String username, String source) {}
    public record Event(Identity actor, Identity target, String oldRank, String newRank, String text) {}
}
