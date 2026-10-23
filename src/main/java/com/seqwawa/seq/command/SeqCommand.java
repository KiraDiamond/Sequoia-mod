package com.seqwawa.seq.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.ConfigManager;
import com.seqwawa.seq.managers.BombShareManager;
import com.seqwawa.seq.managers.DiscordRankChatDecorator;
import com.seqwawa.seq.managers.DiscordRankService;
import com.seqwawa.seq.managers.GuildRewardAutomationManager;
import com.seqwawa.seq.managers.LeaderboardBadgeService;
import com.seqwawa.seq.managers.PartyFinderManager;
import com.seqwawa.seq.managers.PartyListing;
import com.seqwawa.seq.managers.RankProfileRoster;
import com.seqwawa.seq.managers.TreasuryOutManager;
import com.seqwawa.seq.managers.WarPlannerManager;
import com.seqwawa.seq.map.GatheringClusterCache;
import com.seqwawa.seq.map.GatheringMapImageService;
import com.seqwawa.seq.map.WorldMapSettings;
import com.seqwawa.seq.model.Activity;
import com.seqwawa.seq.model.AllyRaidReport;
import com.seqwawa.seq.model.Listing;
import com.seqwawa.seq.model.PartyRole;
import com.seqwawa.seq.network.ApiClient;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.network.WynncraftServerPolicy;
import com.seqwawa.seq.network.auth.AuthException;
import com.seqwawa.seq.network.auth.UnlinkedAccountWarning;
import com.seqwawa.seq.ui.PartyFinderScreen;
import com.seqwawa.seq.utils.PlayerNameCache;

public class SeqCommand {

        private static final List<String> ROLE_SUGGESTIONS = List.of("dps", "healer", "tank", "other");
        private static final List<String> TREASURY_AMOUNT_SUGGESTIONS = List.of(
                        "50",
                        "50le",
                        "50s",
                        "2stx5le",
                        "2stx5le+1stx5le+4stx4le");
        private static final int DEFAULT_ALLY_RAID_CUTOFF_MINUTES = 30;
        private static final DateTimeFormatter ALLY_RAID_TIME_FORMAT =
                        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
        private static final Set<String> CASE_INSENSITIVE_ROOTS = Set.of("seq", "allyraids", "e", "a");
        private static volatile CommandNode<FabricClientCommandSource> commandRoot;

        public static void register() {
                ClientCommandRegistrationCallback.EVENT.register(SeqCommand::registerCommands);
                ClientSendMessageEvents.MODIFY_COMMAND.register(SeqCommand::normalizeCommandCapitalization);
        }

        static void registerCommands(
                        CommandDispatcher<FabricClientCommandSource> dispatcher,
                        CommandBuildContext registryAccess) {
                var root = ClientCommandManager.literal("seq")
                                .executes(ctx -> {
                                        SeqClient.openMainScreen();
                                        return 1;
                                })
                                .then(ClientCommandManager.literal("connect")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().connectManually();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("disconnect")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().disconnectManually();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("connected")
                                                .executes(ctx -> {
                                                        if (!ConnectionManager.isConnected()) {
                                                                sendFeedback(
                                                                                ctx.getSource(),
                                                                                "Not connected. Use /seq connect first.");
                                                                return 0;
                                                        }
                                                        ConnectionManager.getInstance().requestConnectedUsers(users -> {
                                                                if (users.isEmpty()) {
                                                                        sendFeedback(ctx.getSource(), "No users connected.");
                                                                        return;
                                                                }

                                                                sendFeedback(
                                                                                ctx.getSource(),
                                                                                "Connected users (" + users.size() + "):");
                                                                for (String user : users) {
                                                                        sendFeedback(ctx.getSource(), "• " + user);
                                                                }
                                                        });
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("status").executes(SeqCommand::runStatus))
                                .then(ClientCommandManager.literal("_dismiss-unlinked")
                                                .then(ClientCommandManager.argument(
                                                                "profile",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::dismissUnlinkedWarning)))
                                .then(ClientCommandManager.literal("logout")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().disconnect();
                                                        SeqClient.getAuthService().clearSession();
                                                        if (SeqClient.getWarPlannerManager() != null) {
                                                                SeqClient.getWarPlannerManager().reset();
                                                        }
                                                        sendFeedback(ctx.getSource(), "Logged out and token cleared.");
                                                        return 1;
                                                }))
                                .then(buildIgnoreCommand())
                                .then(buildUnignoreCommand())
                                .then(buildRequestCommand())
                                .then(buildEmeraldRewardCommand("e"))
                                .then(buildEmeraldRewardCommand("emeralds"))
                                .then(buildAspectRewardCommand())
                                .then(buildTomeRewardCommand())
                                .then(buildBombCommand())
                                .then(buildTreasuryCommand(SeqCommand::runTreasuryOut))
                                .then(buildBadgeCommand("badges"))
                                .then(buildBadgeCommand("badge"))
                                .then(buildRankCommand("ranks"))
                                .then(buildRankCommand("rank"))
                                .then(ClientCommandManager.literal("settings")
                                                .executes(ctx -> {
                                                        SeqClient.openSettingsScreen();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("achievement")
                                                .executes(ctx -> {
                                                        SeqClient.openAchievementsScreen();
                                                        return 1;
                                                }))
                                .then(buildMapCommand())
                                .then(buildWarCommand())
                                .then(buildAllyRaidsCommand("allyraids"))
                                .then(ClientCommandManager.literal("ingredients")
                                                .executes(SeqCommand::openIngredientGuideScreen))
                                .then(ClientCommandManager.literal("ingredient")
                                                .executes(SeqCommand::openIngredientGuideScreen))
                                .then(ClientCommandManager.literal("members")
                                                .executes(SeqCommand::openGuildMembersScreen))
                                .then(ClientCommandManager.literal("member")
                                                .executes(SeqCommand::openGuildMembersScreen))
                                .then(buildPartyCommand("party"))
                                .then(buildPartyCommand("p"));

                dispatcher.register(root);
                dispatcher.register(buildAllyRaidsCommand("allyraids"));
                dispatcher.register(buildEmeraldRewardCommand("e"));
                dispatcher.register(ClientCommandManager.literal("a")
                                .executes(ctx -> runQueuedGuildReward(
                                                ctx,
                                                GuildRewardAutomationManager.RewardType.ASPECT,
                                                30)));
                commandRoot = dispatcher.getRoot();
        }

        static String normalizeCommandCapitalization(String command) {
                CommandNode<FabricClientCommandSource> root = commandRoot;
                if (command == null || command.isBlank() || root == null) {
                        return command;
                }

                StringReader reader = new StringReader(command);
                int firstTokenStart = skipSpaces(reader);
                if (!reader.canRead()) {
                        return command;
                }
                String firstToken = reader.readUnquotedString();
                if (!CASE_INSENSITIVE_ROOTS.contains(firstToken.toLowerCase(Locale.ROOT))) {
                        return command;
                }
                reader.setCursor(firstTokenStart);

                StringBuilder normalized = new StringBuilder(command);
                CommandNode<FabricClientCommandSource> current = root;
                while (reader.canRead()) {
                        int tokenStart = skipSpaces(reader);
                        if (!reader.canRead()) {
                                break;
                        }

                        String token = reader.readUnquotedString();
                        LiteralCommandNode<FabricClientCommandSource> literal = current.getChildren().stream()
                                        .filter(LiteralCommandNode.class::isInstance)
                                        .map(node -> (LiteralCommandNode<FabricClientCommandSource>) node)
                                        .filter(node -> node.getLiteral().equalsIgnoreCase(token))
                                        .findFirst()
                                        .orElse(null);
                        if (literal != null) {
                                normalized.replace(tokenStart, reader.getCursor(), literal.getLiteral());
                                current = literal;
                                continue;
                        }

                        reader.setCursor(tokenStart);
                        ArgumentCommandNode<FabricClientCommandSource, ?> argument = parseArgument(current, command, reader);
                        if (argument == null) {
                                break;
                        }
                        current = argument;
                }
                return normalized.toString();
        }

        private static int skipSpaces(StringReader reader) {
                while (reader.canRead() && reader.peek() == ' ') {
                        reader.skip();
                }
                return reader.getCursor();
        }

        private static ArgumentCommandNode<FabricClientCommandSource, ?> parseArgument(
                        CommandNode<FabricClientCommandSource> current,
                        String command,
                        StringReader reader) {
                for (CommandNode<FabricClientCommandSource> child : current.getChildren()) {
                        if (!(child instanceof ArgumentCommandNode<FabricClientCommandSource, ?> argument)) {
                                continue;
                        }

                        StringReader candidate = new StringReader(command);
                        candidate.setCursor(reader.getCursor());
                        try {
                                argument.getType().parse(candidate);
                                reader.setCursor(candidate.getCursor());
                                return argument;
                        } catch (CommandSyntaxException ignored) {
                                // Try the next argument branch.
                        }
                }
                return null;
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildPartyCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(ctx -> openPartyScreen())
                                .then(ClientCommandManager.literal("create-ui")
                                                .executes(ctx -> openPartyCreateScreen()))
                                .then(ClientCommandManager.literal("list")
                                                .executes(SeqCommand::runPartyList))
                                .then(ClientCommandManager.literal("status")
                                                .executes(SeqCommand::runPartyStatus))
                                .then(ClientCommandManager.literal("create")
                                                .then(ClientCommandManager.argument(
                                                                "activities",
                                                                StringArgumentType.greedyString())
                                                                .suggests(SeqCommand::suggestActivities)
                                                                .executes(SeqCommand::runPartyCreate)))
                                .then(ClientCommandManager.literal("update")
                                                .then(ClientCommandManager.argument(
                                                                "activities",
                                                                StringArgumentType.greedyString())
                                                                .suggests(SeqCommand::suggestActivities)
                                                                .executes(SeqCommand::runPartyUpdate)))
                                .then(buildPartyJoinCommand())
                                .then(ClientCommandManager.literal("deny")
                                                .then(ClientCommandManager.argument(
                                                                "listingId",
                                                                LongArgumentType.longArg(1))
                                                                .executes(SeqCommand::runPartyDeny)))
                                .then(ClientCommandManager.literal("leave")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .leavePartyFromCommand())))
                                .then(ClientCommandManager.literal("invite")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyInvite)))
                                .then(ClientCommandManager.literal("revoke-invite")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyRevokeInvite)))
                                .then(ClientCommandManager.literal("reserve")
                                                .then(ClientCommandManager.argument(
                                                                "count",
                                                                IntegerArgumentType.integer(0))
                                                                .executes(SeqCommand::runPartyReserve)))
                                .then(ClientCommandManager.literal("open")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .reopenPartyFromCommand())))
                                .then(ClientCommandManager.literal("close")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .closePartyFromCommand())))
                                .then(ClientCommandManager.literal("extend")
                                        .executes(ctx -> relayCommandResult(
                                                ctx,
                                                SeqClient.getPartyFinderManager()
                                                                                .extendPartyFromCommand()))
                                                .then(ClientCommandManager.argument(
                                                                "listingId",
                                                                LongArgumentType.longArg(1))
                                                        .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                        .extendPartyFromCommand(LongArgumentType.getLong(
                                                                                ctx, "listingId"))))))
                                .then(ClientCommandManager.literal("disband")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .disbandPartyFromCommand())))
                                .then(ClientCommandManager.literal("role")
                                                .then(ClientCommandManager.argument(
                                                                "role",
                                                                StringArgumentType.word())
                                                                .suggests(SeqCommand::suggestRoles)
                                                                .executes(SeqCommand::runPartyRole)))
                                .then(ClientCommandManager.literal("kick")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyKick)))
                                .then(ClientCommandManager.literal("promote")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyPromote)))
                                .then(ClientCommandManager.literal("invite-all")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .inviteAllCurrentMembersFromCommand())))
                                .then(ClientCommandManager.literal("scan")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .scanCurrentWynnPartyFromCommand())));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildWarCommand() {
                return ClientCommandManager.literal("war")
                                .requires(source -> isWarPlannerAuthorized())
                                .executes(SeqCommand::openWarPlanner)
                                .then(ClientCommandManager.literal("available")
                                                .then(ClientCommandManager.argument(
                                                                "minutes", IntegerArgumentType.integer(1, 1440))
                                                                .executes(SeqCommand::setWarAvailability)))
                                .then(ClientCommandManager.literal("unavailable")
                                                .executes(SeqCommand::clearWarAvailability));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildAllyRaidsCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(ctx -> runAllyRaids(ctx, DEFAULT_ALLY_RAID_CUTOFF_MINUTES))
                                .then(ClientCommandManager.argument(
                                                "cutoff", IntegerArgumentType.integer(20, 120))
                                                .executes(ctx -> runAllyRaids(
                                                                ctx,
                                                                IntegerArgumentType.getInteger(ctx, "cutoff"))));
        }

        private static int runAllyRaids(CommandContext<FabricClientCommandSource> ctx, int cutoffMinutes) {
                FabricClientCommandSource source = ctx.getSource();
                ApiClient.getInstance().getAllyRaidReport(cutoffMinutes).whenComplete((report, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Could not load ally raid coverage: "
                                                + describeApiFailure(error, "Backend request failed."));
                                return;
                        }
                        renderAllyRaidReport(source, report);
                });
                return 1;
        }

        private static void renderAllyRaidReport(
                        FabricClientCommandSource source, AllyRaidReport report) {
                sendFeedback(
                                source,
                                "Ally raid coverage | Cutoff " + report.cutoffMinutes() + "m | Recent "
                                                + report.recent().size() + " | Permanent "
                                                + report.protectedAllies().size() + " | Review "
                                                + report.safeToReview().size());
                renderAllyRaidSection(source, "Permanent allies / do not remove", report.protectedAllies(), report);
                renderAllyRaidSection(source, "Raided recently", report.recent(), report);
                renderAllyRaidSection(source, "Safe to unally review", report.safeToReview(), report);
                if (!report.unavailable().isEmpty()) {
                        renderAllyRaidSection(source, "Not assessed", report.unavailable(), report);
                }
                sendFeedback(source, "Mod reports only. Review before changing alliances.");
        }

        private static void renderAllyRaidSection(
                        FabricClientCommandSource source,
                        String title,
                        List<AllyRaidReport.GuildActivity> activities,
                        AllyRaidReport report) {
                sendFeedback(source, title + " (" + activities.size() + ")");
                if (activities.isEmpty()) {
                        sendFeedback(source, "• None");
                        return;
                }
                for (AllyRaidReport.GuildActivity activity : activities) {
                        sendFeedback(source, "• " + formatAllyRaidActivity(activity, report.cutoffMinutes()));
                }
        }

        private static String formatAllyRaidActivity(
                        AllyRaidReport.GuildActivity activity, int cutoffMinutes) {
                String guild = activity.guildName();
                if (activity.guildPrefix() != null && !activity.guildPrefix().isBlank()) {
                        guild += " [" + activity.guildPrefix() + "]";
                }
                if (!activity.rosterAvailable()) {
                        return guild + ": roster unavailable";
                }
                if (activity.lastRaidedAt() == null) {
                        return guild + ": no shared raid observed inside the " + cutoffMinutes + "m cutoff";
                }
                String runs = activity.raidCount() == 1 ? "1 observed run" : activity.raidCount() + " observed runs";
                return guild + ": " + formatAllyRaidTime(activity.lastRaidedAt()) + " • " + runs;
        }

        private static String formatAllyRaidTime(Instant lastRaidedAt) {
                Duration elapsed = Duration.between(lastRaidedAt, Instant.now());
                if (elapsed.isNegative() || elapsed.toMinutes() < 1) {
                        return "just now (" + ALLY_RAID_TIME_FORMAT.format(lastRaidedAt) + ")";
                }
                long minutes = elapsed.toMinutes();
                String relative = minutes < 60
                                ? minutes + "m ago"
                                : minutes < 1_440
                                                ? minutes / 60 + "h " + minutes % 60 + "m ago"
                                                : minutes / 1_440 + "d ago";
                return relative + " (" + ALLY_RAID_TIME_FORMAT.format(lastRaidedAt) + ")";
        }

        private static boolean isWarPlannerAuthorized() {
                WarPlannerManager manager = SeqClient.getWarPlannerManager();
                return manager != null && manager.isAuthorized();
        }

        private static int openWarPlanner(CommandContext<FabricClientCommandSource> ctx) {
                if (authorizedWarPlannerManager(ctx) == null) return 0;
                SeqClient.openWarPlannerScreen();
                return 1;
        }

        private static int setWarAvailability(CommandContext<FabricClientCommandSource> ctx) {
                WarPlannerManager manager = authorizedWarPlannerManager(ctx);
                if (manager == null) return 0;
                int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                relayWarPlannerResult(ctx, manager.setAvailability(minutes));
                return 1;
        }

        private static int clearWarAvailability(CommandContext<FabricClientCommandSource> ctx) {
                WarPlannerManager manager = authorizedWarPlannerManager(ctx);
                if (manager == null) return 0;
                relayWarPlannerResult(ctx, manager.clearAvailability());
                return 1;
        }

        private static WarPlannerManager authorizedWarPlannerManager(
                        CommandContext<FabricClientCommandSource> ctx) {
                WarPlannerManager manager = SeqClient.getWarPlannerManager();
                if (manager == null || !manager.isAuthorized()) {
                        sendFeedback(ctx.getSource(), "War planner access is limited to authorized Sequoia members.");
                        return null;
                }
                return manager;
        }

        private static void relayWarPlannerResult(
                        CommandContext<FabricClientCommandSource> ctx,
                        CompletableFuture<WarPlannerManager.ActionResult> future) {
                future.whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(ctx.getSource(), "War planner request failed.");
                        } else if (result != null && result.message() != null && !result.message().isBlank()) {
                                sendFeedback(ctx.getSource(), result.message());
                        }
                });
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildIgnoreCommand() {
                return ClientCommandManager.literal("ignore")
                                .executes(SeqCommand::runIgnoredBridgeUsersList)
                                .then(ClientCommandManager.argument(
                                                "username",
                                                StringArgumentType.word())
                                                .executes(SeqCommand::runIgnoreBridgeUser));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildUnignoreCommand() {
                return ClientCommandManager.literal("unignore")
                                .executes(SeqCommand::runIgnoredBridgeUsersList)
                                .then(ClientCommandManager.argument(
                                                "username",
                                                StringArgumentType.word())
                                                .suggests(SeqCommand::suggestIgnoredBridgeUsers)
                                                .executes(SeqCommand::runUnignoreBridgeUser));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildBombCommand() {
                return ClientCommandManager.literal("bomb")
                                .then(ClientCommandManager.literal("_share")
                                                .then(ClientCommandManager.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                                .executes(ctx -> SeqClient.getBombShareManager()
                                                                                .sharePrompt(StringArgumentType
                                                                                                .getString(
                                                                                                                ctx,
                                                                                                                "requestId")))))
                                .then(ClientCommandManager.literal("_mute-requests")
                                                .executes(ctx -> SeqClient.getBombShareManager().muteRequests()))
                                .then(ClientCommandManager.argument(
                                                "selectors",
                                                StringArgumentType.greedyString())
                                                .suggests(SeqCommand::suggestBombSelectors)
                                                .executes(ctx -> SeqClient.getBombShareManager()
                                                                .requestBombShare(StringArgumentType.getString(
                                                                                ctx,
                                                                                "selectors"))));
        }

        static <S> LiteralArgumentBuilder<S> buildTreasuryCommand(TreasuryCommandExecutor<S> executor) {
                RequiredArgumentBuilder<S, String> reasonArgument = RequiredArgumentBuilder
                                .<S, String>argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executor.execute(
                                                ctx,
                                                new TreasuryCommandArguments(
                                                                StringArgumentType.getString(ctx, "amount"),
                                                                StringArgumentType.getString(ctx, "payouter"),
                                                                StringArgumentType.getString(ctx, "reason"))));
                RequiredArgumentBuilder<S, String> payouterArgument = RequiredArgumentBuilder
                                .<S, String>argument("payouter", StringArgumentType.word())
                                .then(reasonArgument);
                RequiredArgumentBuilder<S, String> amountArgument = RequiredArgumentBuilder
                                .<S, String>argument("amount", StringArgumentType.word())
                                .suggests(SeqCommand::suggestTreasuryAmounts)
                                .then(payouterArgument);
                return LiteralArgumentBuilder.<S>literal("treasury")
                                .then(LiteralArgumentBuilder.<S>literal("out").then(amountArgument));
        }

        private static int runTreasuryOut(
                        CommandContext<FabricClientCommandSource> ctx, TreasuryCommandArguments arguments) {
                String activeUsername = SeqClient.mc == null || SeqClient.mc.getUser() == null
                                ? null
                                : SeqClient.mc.getUser().getName();
                TreasuryOutManager manager = SeqClient.getTreasuryOutManager();
                if (manager == null) {
                        sendFeedback(ctx.getSource(), "Treasury OUT is unavailable until SeqMod finishes loading.");
                        return 0;
                }
                boolean submitted = manager.submit(
                                activeUsername,
                                ConnectionManager.isTreasuryOutConnected(),
                                arguments.amount(),
                                arguments.payouter(),
                                arguments.reason(),
                                message -> sendFeedback(ctx.getSource(), message));
                return submitted ? 1 : 0;
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildRequestCommand() {
                return ClientCommandManager.literal("request")
                                .then(ClientCommandManager.literal("aspects")
                                                .executes(ctx -> runRewardQueueRequest(ctx, "aspect", null)))
                                .then(ClientCommandManager.literal("tome")
                                                .then(ClientCommandManager.argument(
                                                                "reason",
                                                                StringArgumentType.greedyString())
                                                                .executes(ctx -> runRewardQueueRequest(
                                                                                ctx,
                                                                                "tome",
                                                                                StringArgumentType.getString(
                                                                                                ctx,
                                                                                                "reason")))));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildEmeraldRewardCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(ctx -> {
                                        SeqClient.getGuildRewardAutomationManager().sendAllEmeraldsToCinfrascitizen();
                                        return 1;
                                });
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildTomeRewardCommand() {
                return ClientCommandManager.literal("tome")
                                .executes(ctx -> runQueuedGuildReward(ctx, GuildRewardAutomationManager.RewardType.TOME, 1))
                                .then(ClientCommandManager.argument(
                                                "username",
                                                StringArgumentType.word())
                                                .executes(ctx -> runDirectTomeReward(
                                                                ctx,
                                                                StringArgumentType.getString(ctx, "username"))));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildAspectRewardCommand() {
                return ClientCommandManager.literal("aspects")
                                .then(ClientCommandManager.argument(
                                                "amount",
                                                LongArgumentType.longArg(1))
                                                .executes(SeqCommand::runQueuedAspectReward)
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runDirectAspectReward)));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildBadgeCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(SeqCommand::runBadgeStatus)
                                .then(ClientCommandManager.literal("status")
                                                .executes(SeqCommand::runBadgeStatus))
                                .then(ClientCommandManager.literal("refresh")
                                                .executes(SeqCommand::runBadgeRefresh));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildRankCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(SeqCommand::runDiscordRankStatus)
                                .then(ClientCommandManager.literal("status")
                                                .executes(SeqCommand::runDiscordRankStatus))
                                .then(ClientCommandManager.literal("refresh")
                                                .executes(SeqCommand::runDiscordRankRefresh))
                                .then(ClientCommandManager.literal("debug")
                                                .executes(SeqCommand::runDiscordRankDebug));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildMapCommand() {
                return ClientCommandManager.literal("map")
                                .executes(SeqCommand::openWorldMapScreen)
                                .then(ClientCommandManager.literal("refresh")
                                                .executes(SeqCommand::runMapImageRefresh))
                                .then(ClientCommandManager.literal("params")
                                                .executes(SeqCommand::runMapParams))
                                .then(ClientCommandManager.literal("eps")
                                                .then(ClientCommandManager.argument(
                                                                "blocks",
                                                                IntegerArgumentType.integer(1, 500))
                                                                .executes(SeqCommand::runMapClusterEps)))
                                .then(buildMapMinSamplesCommand("minSamples"))
                                .then(ClientCommandManager.literal("reset")
                                                .executes(SeqCommand::runMapClusterReset))
                                .then(ClientCommandManager.literal("debug")
                                                .executes(SeqCommand::runMapDebugToggle))
                                .then(ClientCommandManager.literal("cache")
                                                .executes(SeqCommand::runMapClusterCacheStatus)
                                                .then(ClientCommandManager.literal("status")
                                                                .executes(SeqCommand::runMapClusterCacheStatus))
                                                .then(ClientCommandManager.literal("cluster")
                                                                .executes(SeqCommand::runMapClusterCacheStatus)
                                                                .then(ClientCommandManager.literal("status")
                                                                                .executes(SeqCommand::runMapClusterCacheStatus))
                                                                .then(ClientCommandManager.literal("clear")
                                                                                .executes(SeqCommand::runMapClusterCacheClear)))
                                                .then(ClientCommandManager.literal("map")
                                                                .executes(SeqCommand::runMapImageCacheStatus)
                                                                .then(ClientCommandManager.literal("status")
                                                                                .executes(SeqCommand::runMapImageCacheStatus))
                                                                .then(ClientCommandManager.literal("clear")
                                                                                .executes(SeqCommand::runMapImageCacheClear))));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildMapMinSamplesCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .then(ClientCommandManager.argument(
                                                "count",
                                                IntegerArgumentType.integer(1, 100))
                                                .executes(SeqCommand::runMapClusterMinSamples));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildPartyJoinCommand() {
                return ClientCommandManager.literal("join")
                                .then(ClientCommandManager.argument(
                                                "listingId",
                                                LongArgumentType.longArg(1))
                                                .executes(ctx -> runPartyJoin(ctx, PartyRole.DPS, null))
                                                .then(ClientCommandManager.literal("token")
                                                                .then(ClientCommandManager.argument(
                                                                                "inviteToken",
                                                                                StringArgumentType.string())
                                                                                .executes(SeqCommand::runPartyJoinWithToken)))
                                                .then(ClientCommandManager.argument(
                                                                "role",
                                                                StringArgumentType.word())
                                                                .suggests(SeqCommand::suggestRoles)
                                                                .executes(SeqCommand::runPartyJoinWithRole)
                                                                .then(ClientCommandManager.literal("token")
                                                                                .then(ClientCommandManager.argument(
                                                                                                "inviteToken",
                                                                                                StringArgumentType
                                                                                                                .string())
                                                                                                .executes(SeqCommand::runPartyJoinWithRoleAndToken)))));
        }

        private static int openPartyScreen() {
                SeqClient.openPartyFinderScreen();
                return 1;
        }

        private static int openWorldMapScreen(CommandContext<FabricClientCommandSource> ctx) {
                SeqClient.openWorldMapScreen();
                return 1;
        }

        private static int openIngredientGuideScreen(CommandContext<FabricClientCommandSource> ctx) {
                SeqClient.openIngredientGuideScreen();
                return 1;
        }

        private static int openGuildMembersScreen(CommandContext<FabricClientCommandSource> ctx) {
                SeqClient.openGuildMembersScreen();
                return 1;
        }

        private static int dismissUnlinkedWarning(CommandContext<FabricClientCommandSource> ctx) {
                UUID profileId;
                try {
                        profileId = UUID.fromString(StringArgumentType.getString(ctx, "profile"));
                } catch (IllegalArgumentException e) {
                        return 0;
                }
                if (!UnlinkedAccountWarning.getInstance().dismiss(profileId)) {
                        return 0;
                }
                sendFeedback(ctx.getSource(), "Hidden for this session.");
                return 1;
        }

        private static int runStatus(CommandContext<FabricClientCommandSource> ctx) {
                boolean connected = ConnectionManager.isConnected();
                String token = SeqClient.getConfigManager().getToken();
                boolean hasToken = token != null && !token.isBlank();
                boolean tokenExpired = hasToken && SeqClient.getAuthService().isTokenExpired();
                String uptime = ConnectionManager.getInstance().getUptimeString();
                AuthException authError = SeqClient.getAuthService().getLastError();

                StringBuilder message = new StringBuilder();
                message.append("Connection: ").append(connected ? "connected" : "disconnected");
                message.append(" | Session: ").append(formatSessionStatus(hasToken, tokenExpired));
                message.append(" | Server: ").append(formatServerScope(WynncraftServerPolicy.currentScope()));

                if (connected && uptime != null) {
                        message.append(" | Uptime: ").append(uptime);
                }
                if (ConnectionManager.isAutoConnectSuppressedByManualDisconnect()) {
                        message.append(" | Auto-connect paused");
                }
                if (authError != null) {
                        message.append(" | Auth issue: ").append(authError.getMessage());
                }

                sendFeedback(ctx.getSource(), message.toString());
                return 1;
        }

        private static String formatSessionStatus(boolean hasToken, boolean tokenExpired) {
                if (!hasToken) {
                        return "not ready";
                }
                return tokenExpired ? "expired" : "ready";
        }

        private static String formatServerScope(WynncraftServerPolicy.Scope scope) {
                return switch (scope) {
                        case MAIN -> "Wynncraft";
                        case UNKNOWN -> "checking";
                        case BLOCKED -> "unsupported";
                };
        }

        private static int runBadgeStatus(CommandContext<FabricClientCommandSource> ctx) {
                String rendererStatus = SeqClient.getSeqBadgeNametagRenderer() == null
                                ? "disabled"
                                : SeqClient.getSeqBadgeNametagRenderer().status();
                sendFeedback(
                                ctx.getSource(),
                                LeaderboardBadgeService.getInstance().status() + " | renderer=" + rendererStatus);
                return 1;
        }

        private static int runBadgeRefresh(CommandContext<FabricClientCommandSource> ctx) {
                return refreshRoster(ctx, "Refreshing leaderboard badges...");
        }

        /**
         * Badges and ranks come from one roster, so either refresh command reloads
         * both.
         */
        private static int refreshRoster(CommandContext<FabricClientCommandSource> ctx, String acknowledgement) {
                RankProfileRoster.getInstance()
                                .refreshAsync()
                                .thenAccept(message -> sendFeedback(ctx.getSource(), message));
                sendFeedback(ctx.getSource(), acknowledgement);
                return 1;
        }

        private static int runDiscordRankStatus(CommandContext<FabricClientCommandSource> ctx) {
                boolean enabled = SeqClient.getShowDiscordRanksSetting() != null
                                && SeqClient.getShowDiscordRanksSetting().getValue();
                sendFeedback(
                                ctx.getSource(),
                                DiscordRankService.getInstance().status()
                                                + " | chat decoration=" + (enabled ? "on" : "off"));
                return 1;
        }

        private static int runDiscordRankRefresh(CommandContext<FabricClientCommandSource> ctx) {
                return refreshRoster(ctx, "Refreshing Discord ranks...");
        }

        private static int runDiscordRankDebug(CommandContext<FabricClientCommandSource> ctx) {
                boolean enabled = !DiscordRankChatDecorator.isDebug();
                DiscordRankChatDecorator.setDebug(enabled);
                sendFeedback(
                                ctx.getSource(),
                                enabled
                                                ? "Discord rank debug on: guild rank decoration details are dumped to the game log."
                                                : "Discord rank debug off.");
                return 1;
        }

        private static int runMapParams(CommandContext<FabricClientCommandSource> ctx) {
                WorldMapSettings settings = WorldMapSettings.getInstance();
                sendFeedback(
                                ctx.getSource(),
                                "Map clustering: " + settings.describe()
                                                + " | cached analyses="
                                                + GatheringClusterCache.getInstance().size());
                return 1;
        }

        private static int runMapClusterEps(CommandContext<FabricClientCommandSource> ctx) {
                int epsBlocks = IntegerArgumentType.getInteger(ctx, "blocks");
                WorldMapSettings.getInstance().setClusterEps(epsBlocks);
                GatheringClusterCache.getInstance().clear();
                sendFeedback(
                                ctx.getSource(),
                                "Map cluster eps set to " + epsBlocks + " blocks. Cluster cache cleared.");
                return 1;
        }

        private static int runMapClusterMinSamples(CommandContext<FabricClientCommandSource> ctx) {
                int minSamples = IntegerArgumentType.getInteger(ctx, "count");
                WorldMapSettings.getInstance().setClusterMinSamples(minSamples);
                GatheringClusterCache.getInstance().clear();
                sendFeedback(
                                ctx.getSource(),
                                "Map cluster minSamples set to " + minSamples + ". Cluster cache cleared.");
                return 1;
        }

        private static int runMapClusterReset(CommandContext<FabricClientCommandSource> ctx) {
                WorldMapSettings.getInstance().resetClusterParams();
                GatheringClusterCache.getInstance().clear();
                sendFeedback(
                                ctx.getSource(),
                                "Map clustering reset to " + WorldMapSettings.getInstance().describe()
                                                + ". Cluster cache cleared.");
                return 1;
        }

        private static int runMapDebugToggle(CommandContext<FabricClientCommandSource> ctx) {
                boolean enabled = WorldMapSettings.getInstance().toggleDebugInfo();
                GatheringMapImageService imageService = GatheringMapImageService.getInstance();
                sendFeedback(
                                ctx.getSource(),
                                "Map debug " + (enabled ? "enabled" : "disabled")
                                                + " | source="
                                                + displayMapImageSource(imageService.imageSource())
                                                + " | status="
                                                + imageService.hqStatus()
                                                + " | url="
                                                + imageService.hqMapUrl());
                return 1;
        }

        private static int runMapClusterCacheStatus(CommandContext<FabricClientCommandSource> ctx) {
                sendFeedback(
                                ctx.getSource(),
                                "Map cluster cache entries: " + GatheringClusterCache.getInstance().size());
                return 1;
        }

        private static int runMapClusterCacheClear(CommandContext<FabricClientCommandSource> ctx) {
                GatheringClusterCache.getInstance().clear();
                sendFeedback(ctx.getSource(), "Map cluster cache cleared.");
                return 1;
        }

        private static int runMapImageCacheStatus(CommandContext<FabricClientCommandSource> ctx) {
                sendFeedback(ctx.getSource(), GatheringMapImageService.getInstance().cacheStatus());
                return 1;
        }

        private static int runMapImageRefresh(CommandContext<FabricClientCommandSource> ctx) {
                sendFeedback(ctx.getSource(), GatheringMapImageService.getInstance().refresh());
                return 1;
        }

        private static int runMapImageCacheClear(CommandContext<FabricClientCommandSource> ctx) {
                sendFeedback(ctx.getSource(), GatheringMapImageService.getInstance().clearCache());
                return 1;
        }

        private static CompletableFuture<Suggestions> suggestBombSelectors(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                for (String suggestion : BombShareManager.suggestionsFor(builder.getRemaining())) {
                        builder.suggest(suggestion);
                }
                return builder.buildFuture();
        }

        private static int openPartyCreateScreen() {
                SeqClient.mc.execute(() -> SeqClient.mc.setScreen(new PartyFinderScreen(SeqClient.mc.screen, true)));
                return 1;
        }

        private static int runPartyList(CommandContext<FabricClientCommandSource> ctx) {
                FabricClientCommandSource source = ctx.getSource();
                PartyFinderManager manager = SeqClient.getPartyFinderManager();
                manager.refreshListingsForCommand().whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected error while loading party listings.");
                                return;
                        }
                        if (!result.success()) {
                                sendFeedback(source, result.message());
                                return;
                        }

                        List<Listing> listings = result.data();
                        if (listings == null || listings.isEmpty()) {
                                sendFeedback(source, "No Sequoia party listings found.");
                                return;
                        }

                        sendFeedback(source, "Party listings (" + listings.size() + "):");
                        Listing currentListing = manager.getCurrentListing();
                        for (Listing listing : listings) {
                                boolean isCurrent = currentListing != null && currentListing.id() == listing.id();
                                sendFeedback(source, formatListingSummary(listing, isCurrent));
                        }
                });
                return 1;
        }

        private static int runQueuedAspectReward(CommandContext<FabricClientCommandSource> ctx) {
                long amount = LongArgumentType.getLong(ctx, "amount");
                return runQueuedGuildReward(ctx, GuildRewardAutomationManager.RewardType.ASPECT, amount);
        }

        private static int runDirectAspectReward(CommandContext<FabricClientCommandSource> ctx) {
                long amount = LongArgumentType.getLong(ctx, "amount");
                String username = StringArgumentType.getString(ctx, "username");
                if (!isValidMinecraftUsername(username)) {
                        sendFeedback(ctx.getSource(), "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }
                SeqClient.getGuildRewardAutomationManager().sendAspects(username, amount);
                return 1;
        }

        private static int runDirectTomeReward(CommandContext<FabricClientCommandSource> ctx, String username) {
                if (!isValidMinecraftUsername(username)) {
                        sendFeedback(ctx.getSource(), "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }
                SeqClient.getGuildRewardAutomationManager().sendTome(username);
                return 1;
        }

        private static int runRewardQueueRequest(
                        CommandContext<FabricClientCommandSource> ctx,
                        String type,
                        String reason) {
                FabricClientCommandSource source = ctx.getSource();
                ApiClient.getInstance().createRewardQueueRequest(type, reason).whenComplete((ignored, error) -> {
                        if (error != null) {
                                sendFeedback(
                                                source,
                                                "Could not submit " + rewardRequestLabel(type) + " request: "
                                                                + describeRewardQueueRequestFailure(error, type));
                                return;
                        }

                        sendFeedback(source, rewardRequestLabel(type) + " request submitted.");
                });
                return 1;
        }

        private static String rewardRequestLabel(String type) {
                return "tome".equals(type) ? "Tome" : "Aspects";
        }

        private static String describeRewardQueueRequestFailure(Throwable error, String type) {
                Throwable cause = unwrapCompletionException(error);
                if (cause instanceof ApiClient.ApiException apiException && apiException.getStatusCode() == 409) {
                        return "You already have a pending "
                                        + rewardRequestLabel(type).toLowerCase(Locale.ROOT)
                                        + " request in the queue.";
                }
                return describeApiFailure(error, "Backend request failed.");
        }

        private static String describeApiFailure(Throwable error, String fallback) {
                Throwable cause = unwrapCompletionException(error);
                if (cause instanceof ApiClient.ApiException apiException) {
                        String message = readApiMessage(apiException.getResponseBody());
                        if (message != null && !message.isBlank()) {
                                return message;
                        }
                }
                String message = cause == null ? null : cause.getMessage();
                return message == null || message.isBlank() ? fallback : message;
        }

        private static Throwable unwrapCompletionException(Throwable error) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                }
                return cause;
        }

        private static String readApiMessage(String responseBody) {
                if (responseBody == null || responseBody.isBlank()) {
                        return null;
                }
                try {
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        if (json.has("message") && json.get("message").isJsonPrimitive()) {
                                return json.get("message").getAsString();
                        }
                        if (json.has("error") && json.get("error").isJsonPrimitive()) {
                                return json.get("error").getAsString();
                        }
                } catch (IllegalStateException | JsonSyntaxException ignored) {
                        return null;
                }
                return null;
        }

        private static int runQueuedGuildReward(
                        CommandContext<FabricClientCommandSource> ctx,
                        GuildRewardAutomationManager.RewardType rewardType,
                        long amount) {
                FabricClientCommandSource source = ctx.getSource();
                String type = rewardType == GuildRewardAutomationManager.RewardType.TOME ? "tome" : "aspect";
                ApiClient.getInstance().getFirstRewardQueueEntry(type).whenComplete((response, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Could not load " + type + " reward queue.");
                                return;
                        }
                        if (response == null || response.entry() == null) {
                                sendFeedback(source, "No pending " + type + " reward queue entry.");
                                return;
                        }

                        ApiClient.RewardQueueEntry entry = response.entry();
                        String username = entry.minecraftUsername();
                        if (!isValidMinecraftUsername(username)) {
                                sendFeedback(source, "Queued " + type + " entry has an invalid IGN.");
                                return;
                        }

                        CompletableFuture<GuildRewardAutomationManager.AutomationResult> automation =
                                        rewardType == GuildRewardAutomationManager.RewardType.TOME
                                                        ? SeqClient.getGuildRewardAutomationManager().sendTome(username)
                                                        : SeqClient.getGuildRewardAutomationManager()
                                                                        .sendAspects(username, amount);
                        automation.whenComplete((result, automationError) -> {
                                if (automationError != null || result == null || !result.success()) {
                                        return;
                                }
                                ApiClient.getInstance().completeRewardQueueEntry(entry.requestId())
                                                .whenComplete((ignored, claimError) -> {
                                                        if (claimError != null) {
                                                                sendFeedback(
                                                                                source,
                                                                                "Reward sent, but queue completion failed for request #"
                                                                                                + entry.requestId()
                                                                                                + ".");
                                                        } else {
                                                                sendFeedback(
                                                                                source,
                                                                                "Completed reward queue request #"
                                                                                                + entry.requestId()
                                                                                                + ".");
                                                        }
                                                });
                        });
                });
                return 1;
        }

        private static int runPartyStatus(CommandContext<FabricClientCommandSource> ctx) {
                FabricClientCommandSource source = ctx.getSource();
                PartyFinderManager manager = SeqClient.getPartyFinderManager();
                manager.refreshListingsForCommand().whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected error while loading party status.");
                                return;
                        }
                        if (!result.success()) {
                                sendFeedback(source, result.message());
                                return;
                        }

                        Listing currentListing = manager.getCurrentListing();
                        if (currentListing == null) {
                                sendFeedback(source, "You are not currently in a Sequoia party.");
                                return;
                        }

                        sendFeedback(source, "Current party: " + formatListingSummary(currentListing, true));
                        sendFeedback(
                                        source,
                                        manager.isPartyLeader()
                                                        ? "You are the party leader."
                                                        : "You are a party member.");
                });
                return 1;
        }

        private static int runPartyCreate(CommandContext<FabricClientCommandSource> ctx) {
                List<String> activities = parseActivitiesInput(
                                StringArgumentType.getString(ctx, "activities"));
                if (activities.isEmpty()) {
                        sendFeedback(ctx.getSource(), "Provide at least one activity.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().createPartyFromCommand(activities));
        }

        private static int runPartyUpdate(CommandContext<FabricClientCommandSource> ctx) {
                List<String> activities = parseActivitiesInput(
                                StringArgumentType.getString(ctx, "activities"));
                if (activities.isEmpty()) {
                        sendFeedback(ctx.getSource(), "Provide at least one activity.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().updatePartyFromCommand(activities));
        }

        private static int runPartyJoin(
                        CommandContext<FabricClientCommandSource> ctx,
                        PartyRole role,
                        String inviteToken) {
                long listingId = LongArgumentType.getLong(ctx, "listingId");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().joinPartyFromCommand(listingId, role, inviteToken));
        }

        private static int runPartyJoinWithRole(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank, Other.");
                        return 0;
                }
                return runPartyJoin(ctx, role, null);
        }

        private static int runPartyJoinWithToken(CommandContext<FabricClientCommandSource> ctx) {
                return runPartyJoin(
                                ctx,
                                PartyRole.DPS,
                                StringArgumentType.getString(ctx, "inviteToken"));
        }

        private static int runPartyJoinWithRoleAndToken(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank, Other.");
                        return 0;
                }
                return runPartyJoin(ctx, role, StringArgumentType.getString(ctx, "inviteToken"));
        }

        private static int runPartyDeny(CommandContext<FabricClientCommandSource> ctx) {
                long listingId = LongArgumentType.getLong(ctx, "listingId");
                sendFeedback(ctx.getSource(), "Dismissed party invite for #" + listingId + ".");
                return 1;
        }

        private static int runIgnoreBridgeUser(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                if (!ConfigManager.isValidBridgeUsername(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }

                if (SeqClient.getConfigManager().addIgnoredBridgeUser(username)) {
                        sendFeedback(ctx.getSource(), "Ignoring Discord bridge messages from " + username.trim() + ".");
                } else {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Already ignoring Discord bridge messages from " + username.trim() + ".");
                }
                return 1;
        }

        private static int runUnignoreBridgeUser(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                if (!ConfigManager.isValidBridgeUsername(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }

                if (SeqClient.getConfigManager().removeIgnoredBridgeUser(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Discord bridge messages from " + username.trim() + " are visible again.");
                } else {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Discord bridge messages from " + username.trim() + " were not ignored.");
                }
                return 1;
        }

        private static int runIgnoredBridgeUsersList(CommandContext<FabricClientCommandSource> ctx) {
                List<String> ignoredUsers = SeqClient.getConfigManager().ignoredBridgeUsers();
                if (ignoredUsers.isEmpty()) {
                        sendFeedback(ctx.getSource(), "No Discord bridge users are ignored.");
                        return 1;
                }

                sendFeedback(ctx.getSource(), "Ignored Discord bridge users: " + String.join(", ", ignoredUsers));
                return 1;
        }

        private static int runPartyInvite(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().createInviteFromCommand(username));
        }

        private static int runPartyRevokeInvite(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().revokeInviteFromCommand(username));
        }

        private static int runPartyReserve(CommandContext<FabricClientCommandSource> ctx) {
                int count = IntegerArgumentType.getInteger(ctx, "count");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().setReservedSlotTargetFromCommand(count));
        }

        private static int runPartyRole(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank, Other.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().changeRoleFromCommand(role));
        }

        private static int runPartyKick(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().kickMemberFromCommand(username));
        }

        private static int runPartyPromote(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().promoteMemberFromCommand(username));
        }

        private static <T> int relayCommandResult(
                        CommandContext<FabricClientCommandSource> ctx,
                        CompletableFuture<PartyFinderManager.CommandResult<T>> future) {
                FabricClientCommandSource source = ctx.getSource();
                future.whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected command failure.");
                                return;
                        }
                        if (result != null && result.message() != null && !result.message().isBlank()) {
                                sendFeedback(source, result.message());
                        }
                });
                return 1;
        }

        private static CompletableFuture<Suggestions> suggestRoles(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                return SharedSuggestionProvider.suggest(ROLE_SUGGESTIONS, builder);
        }

        private static <S> CompletableFuture<Suggestions> suggestTreasuryAmounts(
                        CommandContext<S> ctx,
                        SuggestionsBuilder builder) {
                return SharedSuggestionProvider.suggest(TREASURY_AMOUNT_SUGGESTIONS, builder);
        }

        private static CompletableFuture<Suggestions> suggestActivities(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                String remaining = builder.getRemaining();
                int lastCommaIndex = remaining.lastIndexOf(',');
                int segmentStart = lastCommaIndex >= 0 ? lastCommaIndex + 1 : 0;
                while (segmentStart < remaining.length() && Character.isWhitespace(remaining.charAt(segmentStart))) {
                        segmentStart++;
                }

                String segment = remaining.substring(segmentStart);
                if (!segment.isEmpty() && (segment.charAt(0) == '"' || segment.charAt(0) == '\'')) {
                        segment = segment.substring(1);
                }

                SuggestionsBuilder segmentBuilder = builder.createOffset(builder.getStart() + segmentStart);
                String loweredSegment = segment.toLowerCase(Locale.ROOT);
                List<String> matches = PartyListing.activityCommandAliases()
                                .stream()
                                .filter(alias -> alias.toLowerCase(Locale.ROOT).startsWith(loweredSegment))
                                .toList();
                return SharedSuggestionProvider.suggest(matches, segmentBuilder);
        }

        private static CompletableFuture<Suggestions> suggestIgnoredBridgeUsers(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                return SharedSuggestionProvider.suggest(SeqClient.getConfigManager().ignoredBridgeUsers(), builder);
        }

        private static List<String> parseActivitiesInput(String rawActivities) {
                List<String> activities = new ArrayList<>();
                if (rawActivities == null || rawActivities.isBlank()) {
                        return activities;
                }

                StringBuilder current = new StringBuilder();
                char activeQuote = 0;
                for (int i = 0; i < rawActivities.length(); i++) {
                        char ch = rawActivities.charAt(i);
                        if ((ch == '"' || ch == '\'') && (activeQuote == 0 || activeQuote == ch)) {
                                activeQuote = activeQuote == 0 ? ch : 0;
                                continue;
                        }
                        if (ch == ',' && activeQuote == 0) {
                                addActivityToken(activities, current.toString());
                                current.setLength(0);
                                continue;
                        }
                        current.append(ch);
                }
                addActivityToken(activities, current.toString());
                return activities;
        }

        private static void addActivityToken(List<String> activities, String rawToken) {
                if (rawToken == null) {
                        return;
                }
                String normalized = rawToken.trim();
                if (normalized.length() >= 2
                                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                                                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
                        normalized = normalized.substring(1, normalized.length() - 1).trim();
                }
                if (!normalized.isEmpty()) {
                        activities.add(normalized);
                }
        }

        static PartyRole parseRole(String rawRole) {
                        if (rawRole == null) {
                                return null;
                        }
                        return switch (rawRole.trim().toLowerCase(Locale.ROOT)) {
                                case "dps" -> PartyRole.DPS;
                                case "healer" -> PartyRole.HEALER;
                                case "tank" -> PartyRole.TANK;
                                case "other" -> PartyRole.OTHER;
                                default -> null;
                        };
        }

        private static boolean isValidMinecraftUsername(String username) {
                return ConfigManager.isValidBridgeUsername(username);
        }

        private static String formatListingSummary(Listing listing, boolean isCurrent) {
                String prefix = isCurrent ? "* " : "• ";
                String activities = listing.resolvedActivities()
                                .stream()
                                .map(Activity::name)
                                .map(PartyListing::backendNameToDisplayName)
                                .reduce((left, right) -> left + ", " + right)
                                .orElse("Unknown Activity");
                String leaderName = PlayerNameCache.resolve(listing.leaderUUID());
                boolean leaderResolvable = leaderName != null
                                && !leaderName.isBlank()
                                && !"Loading...".equalsIgnoreCase(leaderName)
                                && !"Unknown".equalsIgnoreCase(leaderName);
                String leaderSegment = leaderResolvable ? " | Leader: " + leaderName : "";

                return prefix + "#" + listing.id()
                                + " | "
                                + activities
                                + " | "
                                + listing.occupiedSlotCount()
                                + "/"
                                + listing.maxPartySize()
                                + " | "
                                + formatEnumLabel(listing.status().name())
                                + leaderSegment;
        }

        private static String formatEnumLabel(String raw) {
                if (raw == null || raw.isBlank()) {
                        return "";
                }
                String lower = raw.toLowerCase(Locale.ROOT);
                return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }

        private static String displayMapImageSource(GatheringMapImageService.Source source) {
                return switch (source) {
                        case NONE -> "none";
                        case CACHED_TILES -> "cached tiles";
                        case CACHED_HQ -> "cached HQ";
                };
        }

        private static void sendFeedback(FabricClientCommandSource source, String message) {
                SeqClient.mc.execute(() -> source.sendFeedback(NotificationAccessor.prefixed(message)));
        }

        record TreasuryCommandArguments(String amount, String payouter, String reason) {}

        @FunctionalInterface
        interface TreasuryCommandExecutor<S> {
                int execute(CommandContext<S> context, TreasuryCommandArguments arguments);
        }

}
