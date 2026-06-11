package app.odango.discordvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

@Plugin(
        id = "discordintegrationvelocity",
        name = "Discord Integration Velocity",
        version = "1.0.0",
        description = "Syncs Discord and Minecraft chat across Velocity servers.",
        authors = {"odango"}
)
public final class DiscordIntegrationVelocityPlugin {
    static final MinecraftChannelIdentifier EVENT_CHANNEL = MinecraftChannelIdentifier.from("discordsync:events");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private PluginConfig config;
    private JDA jda;
    private TextChannel discordChannel;
    private Webhook minecraftChatWebhook;

    @Inject
    public DiscordIntegrationVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = PluginConfig.load(dataDirectory, logger);
        proxy.getChannelRegistrar().register(EVENT_CHANNEL);
        startDiscord();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (config != null && config.embeds.proxyStatus) {
            sendAuthorEmbedNow("Velocity proxy が停止しました", null, onlineSummary(), config.embeds.proxyStopColor);
        }
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    @Subscribe
    public void onMinecraftChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String serverName = currentServerName(player).orElse("unknown");
        String message = event.getMessage();

        if (config.minecraft.broadcastMinecraftChatToOtherServers) {
            Component component = Component.text()
                    .append(Component.text("[", NamedTextColor.DARK_GRAY))
                    .append(Component.text(serverName, NamedTextColor.AQUA))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(message, NamedTextColor.WHITE))
                    .build();

            for (Player target : proxy.getAllPlayers()) {
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                Optional<String> targetServer = currentServerName(target);
                if (targetServer.isEmpty() || targetServer.get().equals(serverName)) {
                    continue;
                }
                if (config.minecraft.shouldSyncServer(targetServer.get())) {
                    target.sendMessage(component);
                }
            }
        }

        sendMinecraftChatToDiscord(player, serverName, message);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (config.embeds.joinLeave) {
            Player player = event.getPlayer();
            sendAuthorEmbed(player.getUsername() + "さんが参加しました", playerAvatarUrl(player), null, config.embeds.joinColor);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (config.embeds.joinLeave) {
            Player player = event.getPlayer();
            sendAuthorEmbed(player.getUsername() + "さんが退出しました", playerAvatarUrl(player), null, config.embeds.leaveColor);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!config.embeds.serverSwitch) {
            return;
        }

        String currentServer = event.getServer().getServerInfo().getName();
        event.getPreviousServer().ifPresent(previousServer -> {
            String previousName = previousServer.getServerInfo().getName();
            if (!previousName.equals(currentServer)) {
                Player player = event.getPlayer();
                sendAuthorEmbed(
                        player.getUsername() + "さんがサーバーを移動しました",
                        playerAvatarUrl(player),
                        "`" + previousName + "` から `" + currentServer + "` に移動しました",
                        config.embeds.switchColor
                );
            }
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(EVENT_CHANNEL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }

        BackendEvent backendEvent = BackendEvent.fromJson(new String(event.getData(), StandardCharsets.UTF_8));
        if (backendEvent == null || !"death".equals(backendEvent.type) || !config.embeds.death) {
            return;
        }

        String serverName = source.getServerInfo().getName();
        String dimension = backendEvent.world == null || backendEvent.world.isBlank() ? "unknown" : backendEvent.world;
        String coordinateLine = "ワールド: `" + dimension + "`  X: `" + backendEvent.x + "`  Y: `" + backendEvent.y + "`  Z: `" + backendEvent.z + "`";
        String tpCommand = "/execute in " + dimension + " run tp " + backendEvent.player + " " + backendEvent.x + " " + backendEvent.y + " " + backendEvent.z;
        String description = "`" + serverName + "` で死亡しました";
        if (backendEvent.deathMessage != null && !backendEvent.deathMessage.isBlank()) {
            description += "\n" + backendEvent.deathMessage;
        }
        description += "\n" + coordinateLine + "\n```text\n" + tpCommand + "\n```";
        sendAuthorEmbed(
                backendEvent.player + "さんが死亡しました",
                playerAvatarUrl(backendEvent.uuid, backendEvent.player),
                description,
                deathColorForWorld(dimension)
        );
    }

    private void startDiscord() {
        if (!config.discord.enabled) {
            logger.info("Discord integration is disabled in config.json.");
            return;
        }
        if (config.discord.token.isBlank() || config.discord.channelId.isBlank()) {
            logger.warn("Discord token/channelId is missing. Edit config.json and restart the proxy.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(config.discord.token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .setActivity(Activity.playing(config.discord.status))
                    .addEventListeners(new DiscordListener())
                    .build();

            jda.awaitReady();
            discordChannel = jda.getTextChannelById(config.discord.channelId);
            if (discordChannel == null) {
                logger.warn("Discord channel {} was not found or is not a text channel.", config.discord.channelId);
            } else {
                logger.info("Discord integration connected to #{}.", discordChannel.getName());
                registerSlashCommands();
                prepareMinecraftChatWebhook();
                if (config.embeds.proxyStatus) {
                    sendAuthorEmbed("Velocity proxy が起動しました", null, onlineSummary(), config.embeds.proxyStartColor);
                }
            }
        } catch (Exception exception) {
            logger.error("Failed to start Discord integration.", exception);
        }
    }

    private void registerSlashCommands() {
        if (!config.discord.slashCommands || jda == null) {
            return;
        }
        jda.upsertCommand(Commands.slash("players", "Show current online players by backend server"))
                .queue(command -> logger.info("Registered Discord slash command /{}.", command.getName()),
                        error -> logger.warn("Failed to register Discord slash command /players.", error));
        jda.upsertCommand(Commands.slash("servers", "Show backend server player counts"))
                .queue(command -> logger.info("Registered Discord slash command /{}.", command.getName()),
                        error -> logger.warn("Failed to register Discord slash command /servers.", error));
        jda.upsertCommand(Commands.slash("where", "Show which backend server a player is connected to")
                        .addOption(OptionType.STRING, "player", "Minecraft player name", true))
                .queue(command -> logger.info("Registered Discord slash command /{}.", command.getName()),
                        error -> logger.warn("Failed to register Discord slash command /where.", error));
    }

    private void prepareMinecraftChatWebhook() {
        TextChannel channel = discordChannel;
        if (!config.discord.minecraftChatWebhook || channel == null) {
            return;
        }
        channel.retrieveWebhooks().queue(webhooks -> {
            Optional<Webhook> existing = webhooks.stream()
                    .filter(webhook -> config.discord.minecraftChatWebhookName.equals(webhook.getName()))
                    .filter(webhook -> webhook.getToken() != null && !webhook.getToken().isBlank())
                    .findFirst();
            if (existing.isPresent()) {
                minecraftChatWebhook = existing.get();
                return;
            }
            channel.createWebhook(config.discord.minecraftChatWebhookName).queue(
                    webhook -> {
                        minecraftChatWebhook = webhook;
                        logger.info("Created Discord webhook {} for Minecraft chat.", webhook.getName());
                    },
                    error -> logger.warn("Failed to create Discord webhook for Minecraft chat. Falling back to bot messages.", error)
            );
        }, error -> logger.warn("Failed to retrieve Discord webhooks. Falling back to bot messages.", error));
    }

    private void sendDiscordMessage(String message) {
        TextChannel channel = discordChannel;
        if (channel == null) {
            return;
        }
        channel.sendMessage(message)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(null, error -> logger.warn("Failed to send Discord message.", error));
    }

    private void sendMinecraftChatToDiscord(Player player, String serverName, String message) {
        Webhook webhook = minecraftChatWebhook;
        String content = "**[" + escapeMarkdown(serverName) + "]** " + escapeMarkdown(message);
        if (config.discord.minecraftChatWebhook && webhook != null) {
            webhook.sendMessage(content)
                    .setUsername("[" + serverName + "] " + player.getUsername())
                    .setAvatarUrl(playerAvatarUrl(player))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(null, error -> {
                        logger.warn("Failed to send Minecraft chat through Discord webhook. Falling back to bot message.", error);
                        sendDiscordMessage("**[" + escapeMarkdown(serverName) + "] " + escapeMarkdown(player.getUsername()) + ":** " + escapeMarkdown(message));
                    });
            return;
        }
        sendDiscordMessage("**[" + escapeMarkdown(serverName) + "] " + escapeMarkdown(player.getUsername()) + ":** " + escapeMarkdown(message));
    }

    private void sendAuthorEmbed(String author, String iconUrl, String description, int color) {
        TextChannel channel = discordChannel;
        if (channel == null) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(author, null, iconUrl)
                .setColor(color)
                .setTimestamp(Instant.now());
        if (description != null && !description.isBlank()) {
            embed.setDescription(description);
        }
        channel.sendMessageEmbeds(embed.build())
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(null, error -> logger.warn("Failed to send Discord embed.", error));
    }

    private void sendAuthorEmbedNow(String author, String iconUrl, String description, int color) {
        TextChannel channel = discordChannel;
        if (channel == null) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(author, null, iconUrl)
                .setColor(color)
                .setTimestamp(Instant.now());
        if (description != null && !description.isBlank()) {
            embed.setDescription(description);
        }
        try {
            channel.sendMessageEmbeds(embed.build())
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .complete();
        } catch (Exception exception) {
            logger.warn("Failed to send Discord embed.", exception);
        }
    }

    private void broadcastDiscordMessage(String author, String content) {
        if (content.isBlank()) {
            return;
        }
        Component component = Component.text()
                .append(Component.text("[Discord] ", NamedTextColor.BLUE))
                .append(Component.text(author, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(content, NamedTextColor.WHITE))
                .build();

        for (Player player : proxy.getAllPlayers()) {
            Optional<String> serverName = currentServerName(player);
            if (serverName.isPresent() && config.minecraft.shouldSyncServer(serverName.get())) {
                player.sendMessage(component);
            }
        }
    }

    private String onlineSummary() {
        return "オンライン: `" + proxy.getPlayerCount() + "` 人";
    }

    private String playersSummary() {
        List<Player> players = proxy.getAllPlayers().stream()
                .sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (players.isEmpty()) {
            return "オンラインプレイヤーはいません。";
        }

        StringBuilder summary = new StringBuilder("オンライン: `").append(players.size()).append("` 人");
        for (String serverName : sortedRegisteredServerNames()) {
            List<String> names = players.stream()
                    .filter(player -> currentServerName(player).map(serverName::equals).orElse(false))
                    .map(Player::getUsername)
                    .toList();
            if (!names.isEmpty()) {
                summary.append("\n`").append(serverName).append("` (`").append(names.size()).append("`): ")
                        .append(String.join(", ", names));
            }
        }
        return summary.toString();
    }

    private String serversSummary() {
        List<String> lines = sortedRegisteredServerNames().stream()
                .map(serverName -> "`" + serverName + "`: `" + countPlayersOnServer(serverName) + "` 人")
                .toList();
        if (lines.isEmpty()) {
            return "登録済み backend server がありません。";
        }
        return String.join("\n", lines);
    }

    private List<String> sortedRegisteredServerNames() {
        return proxy.getAllServers().stream()
                .map(server -> server.getServerInfo().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private long countPlayersOnServer(String serverName) {
        return proxy.getAllPlayers().stream()
                .filter(player -> currentServerName(player).map(serverName::equals).orElse(false))
                .count();
    }

    private String whereSummary(String playerName) {
        Optional<Player> player = proxy.getPlayer(playerName);
        if (player.isEmpty()) {
            return "`" + playerName + "` はオンラインではありません。";
        }
        return "`" + player.get().getUsername() + "` は `" + currentServerName(player.get()).orElse("unknown") + "` にいます。";
    }

    private int deathColorForWorld(String world) {
        String normalizedWorld = world.toLowerCase();
        if (normalizedWorld.contains("the_nether") || normalizedWorld.contains("nether")) {
            return config.embeds.netherDeathColor;
        }
        if (normalizedWorld.contains("the_end") || normalizedWorld.contains("end")) {
            return config.embeds.endDeathColor;
        }
        if (normalizedWorld.contains("overworld")) {
            return config.embeds.overworldDeathColor;
        }
        return config.embeds.deathColor;
    }

    private static List<String> attachmentUrls(Message message) {
        List<String> urls = new ArrayList<>();
        urls.addAll(message.getAttachments().stream()
                .map(Message.Attachment::getUrl)
                .toList());
        urls.addAll(message.getStickers().stream()
                .map(sticker -> sticker.getIconUrl())
                .filter(url -> url != null && !url.isBlank())
                .toList());
        return urls;
    }

    private Optional<String> currentServerName(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
    }

    private static String playerAvatarUrl(Player player) {
        return playerAvatarUrl(player.getUniqueId().toString(), player.getUsername());
    }

    private static String playerAvatarUrl(String uuid, String fallbackName) {
        String avatarId = uuid == null || uuid.isBlank() ? fallbackName : uuid;
        return "https://mc-heads.net/avatar/" + avatarId;
    }

    private static String escapeMarkdown(String input) {
        return input.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~");
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(@NotNull MessageReceivedEvent event) {
            if (!config.minecraft.broadcastDiscordMessages) {
                return;
            }
            if (event.getAuthor().isBot() || event.isWebhookMessage()) {
                return;
            }
            if (!event.getChannel().getId().equals(config.discord.channelId)) {
                return;
            }
            Message message = event.getMessage();
            String content = message.getContentDisplay().trim();
            List<String> attachments = attachmentUrls(message);
            if (!attachments.isEmpty()) {
                String attachmentText = attachments.stream()
                        .map(url -> "添付: " + url)
                        .collect(Collectors.joining(" "));
                content = content.isBlank() ? attachmentText : content + " " + attachmentText;
            }
            if (content.isBlank()) {
                return;
            }
            broadcastDiscordMessage(event.getAuthor().getEffectiveName(), content);
        }

        @Override
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
            if (!config.discord.slashCommands || !event.getChannel().getId().equals(config.discord.channelId)) {
                return;
            }

            switch (event.getName()) {
                case "players" -> event.reply(playersSummary()).setEphemeral(false).queue();
                case "servers" -> event.reply(serversSummary()).setEphemeral(false).queue();
                case "where" -> {
                    OptionMapping option = event.getOption("player");
                    String playerName = option == null ? "" : option.getAsString().trim();
                    event.reply(whereSummary(playerName)).setEphemeral(false).queue();
                }
                default -> {
                }
            }
        }
    }
}
