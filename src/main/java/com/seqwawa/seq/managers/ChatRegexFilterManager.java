package com.seqwawa.seq.managers;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/**
 * Filters incoming chat against independently configurable built-in patterns.
 */
public final class ChatRegexFilterManager {
    private static final String SETTINGS_CATEGORY = "chat_filters";
    private static final String CAT_USERNAME = "a3pki";
    private static final String GAZ_USERNAME = "gazthecat";
    private static final Pattern ECONOMY_ACTIVITY_PATTERN = Pattern.compile(
            "^(?:"
                    + "[^:]{1,64} changed \\d+ (?:upgrades|bonuses) on .+"
                    + "|[^:]{1,64} changed (?:the global tax|the (?:ally )?tax of .+) to \\d+%"
                    + "|[^:]{1,64} changed the style of .+ to (?:fastest|cheapest)"
                    + "|[^:]{1,64} changed (?:the global borders|the borders of .+) to (?:open|close)"
                    + "|[^:]{1,64} set the guild headquarters to .+"
                    + "|[^:]{1,64} set .+ (?:bonus|upgrade) to level \\d+ on .+"
                    + "|[^:]{1,64} removed .+ (?:bonus|upgrade) from .+"
                    + "|[^:]{1,64} applied the loadout(?:\\s+|:).+ on .+"
                    + ")$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ECONOMY_RESOURCE_ALERT_PATTERN = Pattern.compile(
            "^(?:"
                    + "Territory .+ (?:is using|is producing) more resources than it can store!"
                    + "|Territory .+ production has stabil(?:ised|ized)"
                    + ")$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_BANK_PATTERN = Pattern.compile(
            "^[^:]{1,64} (?:deposited \\d+x .+ to|withdrew \\d+x .+ from) "
                    + "the Guild Bank \\([^)]+\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK_DMS_PATTERN =
            Pattern.compile("\\bcheck(?:\\s+your)?\\s+d\\s*m\\s*s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAT_AUTHOR_PATTERN = Pattern.compile(
            "^\\s*(?:<\\d+>\\s*)?(?<author>[a-zA-Z0-9_][a-zA-Z0-9_ ]*[a-zA-Z0-9_]|[a-zA-Z0-9_]{3,16})\\s*:");

    private final Setting.BooleanSetting enabledSetting =
            new Setting.BooleanSetting("enable_regex_filters", SETTINGS_CATEGORY, false);
    private final Setting.BooleanSetting economyAlertsOnlySetting;
    private final List<BuiltInFilter> builtInFilters;
    private final BooleanSupplier easterEggsEnabled;
    private final Runnable gazDeathMessageEffect;
    private boolean hooksRegistered;

    public ChatRegexFilterManager() {
        this(ChatRegexFilterManager::easterEggsEnabled, GazDeathMessageEffect::showForLocalPlayer);
    }

    ChatRegexFilterManager(BooleanSupplier easterEggsEnabled) {
        this(easterEggsEnabled, () -> {});
    }

    ChatRegexFilterManager(BooleanSupplier easterEggsEnabled, Runnable gazDeathMessageEffect) {
        this.easterEggsEnabled = Objects.requireNonNull(easterEggsEnabled);
        this.gazDeathMessageEffect = Objects.requireNonNull(gazDeathMessageEffect);
        Setting.BooleanSetting economySetting =
                new Setting.BooleanSetting("economy", SETTINGS_CATEGORY, false);
        economySetting.setParentSetting(enabledSetting);
        economyAlertsOnlySetting =
                new Setting.BooleanSetting("economy_resource_alerts_only", SETTINGS_CATEGORY, false);
        economyAlertsOnlySetting.setParentSetting(economySetting);
        Setting.BooleanSetting guildBankSetting =
                new Setting.BooleanSetting("guild_bank", SETTINGS_CATEGORY, false);
        guildBankSetting.setParentSetting(enabledSetting);
        Setting.BooleanSetting removeTheCatSetting =
                new Setting.BooleanSetting("remove_the_cat", SETTINGS_CATEGORY, false);
        removeTheCatSetting.setPresentation("Remove the cat", null, null);
        removeTheCatSetting.setParentSetting(enabledSetting);
        removeTheCatSetting.setVisibilityCondition(easterEggsEnabled);
        builtInFilters = List.of(
                new BuiltInFilter(
                        "economy",
                        economySetting,
                        (message, normalized) -> matchesEconomy(normalized)),
                new BuiltInFilter(
                        "guild_bank",
                        guildBankSetting,
                        (message, normalized) -> GUILD_BANK_PATTERN.matcher(normalized).matches()),
                new BuiltInFilter(
                        "remove_the_cat",
                        removeTheCatSetting,
                        (message, normalized) -> easterEggsEnabled.getAsBoolean() && isMessageFromCat(message)));
    }

    public List<Setting<?>> settings() {
        List<Setting<?>> settings = new ArrayList<>(2 + builtInFilters.size());
        settings.add(enabledSetting);
        for (BuiltInFilter filter : builtInFilters) {
            settings.add(filter.enabledSetting());
            if (filter.id().equals("economy")) {
                settings.add(economyAlertsOnlySetting);
            }
        }
        return List.copyOf(settings);
    }

    public Setting.BooleanSetting enabledSetting() {
        return enabledSetting;
    }

    public List<BuiltInFilter> builtInFilters() {
        return builtInFilters;
    }

    Setting.BooleanSetting economyAlertsOnlySetting() {
        return economyAlertsOnlySetting;
    }

    public void registerIncomingHooks() {
        if (hooksRegistered) {
            return;
        }
        hooksRegistered = true;
        ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> overlay || shouldAllowIncoming(message));
        ClientReceiveMessageEvents.ALLOW_CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) ->
                        shouldAllowIncoming(message));
    }

    public boolean shouldFilter(String message) {
        return shouldFilter(Component.literal(message == null ? "" : message));
    }

    public boolean shouldFilter(Component message) {
        if (!enabledSetting.getValue()) {
            return false;
        }
        String normalizedMessage =
                PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalizedMessage.isEmpty()) {
            return false;
        }
        for (BuiltInFilter filter : builtInFilters) {
            if (filter.matches(message, normalizedMessage)) {
                return true;
            }
        }
        return false;
    }

    boolean shouldAllowIncoming(Component message) {
        if (easterEggsEnabled.getAsBoolean() && matchesGazDmReminder(message)) {
            gazDeathMessageEffect.run();
        }
        return !shouldFilter(message);
    }

    private static boolean matchesGazDmReminder(Component message) {
        if (message == null) {
            return false;
        }
        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);
        return parsed != null
                && GAZ_USERNAME.equals(parsed.username().toLowerCase(Locale.ROOT))
                && CHECK_DMS_PATTERN.matcher(normalizeGazReminder(parsed.message())).find();
    }

    private static boolean isMessageFromCat(Component message) {
        PacketNameResolver resolver = PacketNameResolver.from(message);
        var matcher = CHAT_AUTHOR_PATTERN.matcher(resolver.text());
        if (!matcher.find()) {
            return false;
        }

        String displayedName = matcher.group("author");
        String username = resolver.resolve(matcher.start("author"), matcher.end("author"),
                PacketNameResolver.Policy.CHAT, "filter.author").username();
        return CAT_USERNAME.equalsIgnoreCase(username) && (PacketNameResolver.validUsername(displayedName.trim()) != null
                || resolver.metadataCovers(username, matcher.start("author"), matcher.end("author")));
    }

    private static String normalizeGazReminder(String message) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message);
        return normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private boolean matchesEconomy(String normalizedMessage) {
        if (ECONOMY_RESOURCE_ALERT_PATTERN.matcher(normalizedMessage).matches()) {
            return true;
        }
        return !economyAlertsOnlySetting.getValue()
                && ECONOMY_ACTIVITY_PATTERN.matcher(normalizedMessage).matches();
    }

    private static boolean easterEggsEnabled() {
        return SeqClient.getEasterEggsSetting() != null
                && SeqClient.getEasterEggsSetting().getValue();
    }

    public static final class BuiltInFilter {
        private final String id;
        private final Setting.BooleanSetting enabledSetting;
        private final BiPredicate<Component, String> matcher;

        private BuiltInFilter(
                String id,
                Setting.BooleanSetting enabledSetting,
                BiPredicate<Component, String> matcher) {
            this.id = id;
            this.enabledSetting = enabledSetting;
            this.matcher = matcher;
        }

        public String id() {
            return id;
        }

        public Setting.BooleanSetting enabledSetting() {
            return enabledSetting;
        }

        private boolean matches(Component message, String normalizedMessage) {
            return enabledSetting.getValue()
                    && matcher.test(message, normalizedMessage);
        }
    }
}
