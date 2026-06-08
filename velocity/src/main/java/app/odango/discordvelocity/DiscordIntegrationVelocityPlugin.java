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
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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

        sendDiscordMessage("**[" + escapeMarkdown(serverName) + "] " + escapeMarkdown(player.getUsername()) + ":** " + escapeMarkdown(message));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (config.embeds.joinLeave) {
            sendEmbed("Player joined", event.getPlayer().getUsername() + " joined the network.", config.embeds.joinColor);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (config.embeds.joinLeave) {
            sendEmbed("Player left", event.getPlayer().getUsername() + " left the network.", config.embeds.leaveColor);
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
                sendEmbed(
                        "Server switch",
                        event.getPlayer().getUsername() + " moved from `" + previousName + "` to `" + currentServer + "`.",
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
        String description = backendEvent.player + " died on `" + serverName + "`";
        if (backendEvent.deathMessage != null && !backendEvent.deathMessage.isBlank()) {
            description += "\n" + backendEvent.deathMessage;
        }
        description += "\nWorld: `" + backendEvent.world + "`  X: `" + backendEvent.x + "`  Y: `" + backendEvent.y + "`  Z: `" + backendEvent.z + "`";
        sendEmbed("Player death", description, config.embeds.deathColor);
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
            }
        } catch (Exception exception) {
            logger.error("Failed to start Discord integration.", exception);
        }
    }

    private void sendDiscordMessage(String message) {
        TextChannel channel = discordChannel;
        if (channel == null) {
            return;
        }
        channel.sendMessage(message).queue(null, error -> logger.warn("Failed to send Discord message.", error));
    }

    private void sendEmbed(String title, String description, int color) {
        TextChannel channel = discordChannel;
        if (channel == null) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description)
                .setColor(color)
                .setTimestamp(Instant.now());
        channel.sendMessageEmbeds(embed.build()).queue(null, error -> logger.warn("Failed to send Discord embed.", error));
    }

    private void broadcastDiscordMessage(String author, String content) {
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

    private Optional<String> currentServerName(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
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
            String content = event.getMessage().getContentDisplay().trim();
            if (content.isBlank()) {
                return;
            }
            broadcastDiscordMessage(event.getAuthor().getEffectiveName(), content);
        }
    }
}
