package app.odango.discordvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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
    private volatile JDA jda;
    private volatile TextChannel discordChannel;
    private DiscordWhitelist whitelist;

    @Inject
    public DiscordIntegrationVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = PluginConfig.load(dataDirectory, logger);
        if (config.loadFailed) {
            return;
        }
        if (config.authentication.enabled) {
            try {
                whitelist = new DiscordWhitelist(dataDirectory.resolve("discord-whitelist.json"));
            } catch (IOException exception) {
                logger.error("Failed to load Discord whitelist. All player logins will be denied.", exception);
            }
        }
        proxy.getChannelRegistrar().register(EVENT_CHANNEL);
        startDiscord();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        String denial = loginDenial(event.getPlayer());
        if (denial != null) {
            event.setResult(ComponentResult.denied(Component.text(denial, NamedTextColor.RED)));
        }
    }

    private String loginDenial(Player player) {
        if (config == null || config.loadFailed) {
            return "設定を読み込めないため接続できません。管理者に連絡してください。";
        }
        if (!config.authentication.enabled) {
            return null;
        }
        if (whitelist == null) {
            return "認証リストを読み込めません。管理者に連絡してください。";
        }
        if (!player.isOnlineMode()) {
            return "Discord認証にはMinecraftのオンライン認証が必要です。管理者に連絡してください。";
        }
        if (whitelist.contains(player.getUniqueId())) {
            return null;
        }
        TextChannel channel = discordChannel;
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED || channel == null) {
            return "Discord認証は現在利用できません。しばらくしてから再接続してください。";
        }
        try {
            String code = whitelist.issueCode(player.getUniqueId(), player.getUsername());
            return "Discord認証が必要です。\n「" + channel.getGuild().getName() + "」で\n/認証 code:"
                    + code + "\nを実行し、再接続してください。\nコードの有効期限は10分です。他人には渡さないでください。";
        } catch (IllegalStateException exception) {
            return exception.getMessage();
        }
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
        String denial = loginDenial(event.getPlayer());
        if (denial != null) {
            event.getPlayer().disconnect(Component.text(denial, NamedTextColor.RED));
            return;
        }
        if (config.embeds.joinLeave) {
            Player player = event.getPlayer();
            sendAuthorEmbed(player.getUsername() + "さんが参加しました", playerAvatarUrl(player), null, config.embeds.joinColor);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (config != null && event.getLoginStatus() == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                && config.embeds.joinLeave) {
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
        String description = "`" + serverName + "` で死亡しました";
        if (backendEvent.deathMessage != null && !backendEvent.deathMessage.isBlank()) {
            description += "\n" + backendEvent.deathMessage;
        }
        description += "\nワールド: `" + backendEvent.world + "`  X: `" + backendEvent.x + "`  Y: `" + backendEvent.y + "`  Z: `" + backendEvent.z + "`";
        sendAuthorEmbed(
                backendEvent.player + "さんが死亡しました",
                playerAvatarUrl(backendEvent.uuid, backendEvent.player),
                description,
                config.embeds.deathColor
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
                if (config.authentication.enabled && whitelist != null) {
                    registerAuthenticationCommands();
                }
            }
        } catch (Exception exception) {
            logger.error("Failed to start Discord integration.", exception);
        }
    }

    private void registerAuthenticationCommands() {
        var guild = discordChannel.getGuild();
        guild.upsertCommand(Commands.slash("認証", "Minecraftの接続画面に表示されたコードで参加を許可します")
                .addOption(OptionType.STRING, "code", "Minecraft接続時の16桁のコード（有効期限10分）", true))
                .queue(command -> logger.info("Registered /認証 in Discord guild {}.", guild.getId()),
                        error -> logger.error("Failed to register /認証.", error));
        guild.upsertCommand(Commands.slash("認証解除", "Minecraftの登録を解除します。他の人の解除にはサーバー管理権限が必要です")
                .addOption(OptionType.USER, "user", "解除するDiscordユーザー（省略時は自分）", false))
                .queue(null, error -> logger.error("Failed to register /認証解除.", error));
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
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
            if (!event.getName().equals("認証") && !event.getName().equals("認証解除")) {
                return;
            }
            TextChannel channel = discordChannel;
            if (!config.authentication.enabled || whitelist == null || channel == null || !event.isFromGuild()
                    || event.getGuild() == null || event.getMember() == null
                    || event.getGuild().getIdLong() != channel.getGuild().getIdLong()) {
                event.reply("このサーバーでは認証を利用できません。").setEphemeral(true).queue();
                return;
            }
            String userId = event.getUser().getId();
            var target = event.getOption("user");
            String targetId = target == null ? userId : target.getAsUser().getId();
            if (event.getName().equals("認証解除") && !targetId.equals(userId)
                    && !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                event.reply("他の人の登録解除には「サーバー管理」権限が必要です。").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue(hook -> {
                try {
                    if (event.getName().equals("認証")) {
                        var code = event.getOption("code");
                        if (code == null) {
                            throw new IllegalArgumentException("Minecraftの接続画面に表示されたコードを指定してください。");
                        }
                        var uuid = whitelist.link(code.getAsString().trim(), userId);
                        hook.editOriginal("認証しました。Minecraftから再接続してください。UUID: " + uuid).queue();
                    } else {
                        var uuid = whitelist.unlink(targetId);
                        proxy.getPlayer(uuid).ifPresent(player -> player.disconnect(
                                Component.text("Discord認証が解除されました。", NamedTextColor.RED)));
                        hook.editOriginal("登録を解除しました。").queue();
                    }
                } catch (IllegalArgumentException exception) {
                    hook.editOriginal(exception.getMessage()).queue();
                } catch (IOException exception) {
                    logger.error("Failed to save Discord whitelist. No change was applied.", exception);
                    hook.editOriginal("保存に失敗しました。登録は変更されていません。管理者に連絡してください。").queue();
                }
            }, error -> logger.warn("Failed to acknowledge authentication command.", error));
        }

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
