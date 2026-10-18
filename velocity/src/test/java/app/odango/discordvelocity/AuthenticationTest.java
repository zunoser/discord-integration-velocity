package app.odango.discordvelocity;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;

/** Offline regression checks; no Minecraft or Discord service is contacted. */
public final class AuthenticationTest {
    private static final UUID FIRST = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Logger LOGGER = stub(Logger.class, (name, args) -> null);

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("discord-auth-test-");
        try {
            storeChecks(root);
            loginChecks(root.resolve("proxy"));
            configChecks(root.resolve("config"));
            commandChecks(root.resolve("commands"));
            System.out.println("Discord authentication checks passed.");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void storeChecks(Path root) throws Exception {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        Path file = root.resolve("whitelist.json");
        DiscordWhitelist store = new DiscordWhitelist(file, Clock.fixed(now, ZoneOffset.UTC));
        assert !store.contains(FIRST);
        String code = store.issueCode(FIRST, "First_Player");
        assert code.matches("[0-9A-F]{16}");
        assert code.equals(store.issueCode(FIRST, "First_Player"));
        expect(IllegalArgumentException.class, () -> store.link("0000000000000000", "123"));
        assert store.link(code.toLowerCase(java.util.Locale.ROOT), "123").equals(FIRST);
        expect(IllegalArgumentException.class, () -> store.link(code, "456"));
        assert new DiscordWhitelist(file).contains(FIRST);
        String secondCode = store.issueCode(SECOND, "Second");
        expect(IllegalArgumentException.class, () -> store.link(secondCode, "123"));
        assert !store.contains(SECOND);
        assert store.unlink("123").equals(FIRST);
        assert !new DiscordWhitelist(file).contains(FIRST);
        expect(IllegalArgumentException.class, () -> store.unlink("123"));
        assert store.link(secondCode, "123").equals(SECOND);

        Clock[] clock = {Clock.fixed(now, ZoneOffset.UTC)};
        DiscordWhitelist expiring = new DiscordWhitelist(root.resolve("expiry.json"), new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return clock[0].instant(); }
        });
        String expired = expiring.issueCode(FIRST, "First");
        clock[0] = Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC);
        expect(IllegalArgumentException.class, () -> expiring.link(expired, "123"));
        assert !expired.equals(expiring.issueCode(FIRST, "First"));

        DiscordWhitelist failed = new DiscordWhitelist(root.resolve("blocked/whitelist.json"));
        String retry = failed.issueCode(FIRST, "First");
        Files.writeString(root.resolve("blocked"), "a file cannot be a parent directory");
        expect(IOException.class, () -> failed.link(retry, "123"));
        assert !failed.contains(FIRST);
        Files.delete(root.resolve("blocked"));
        failed.link(retry, "123");
        Files.delete(root.resolve("blocked/whitelist.json"));
        Files.createDirectory(root.resolve("blocked/whitelist.json"));
        Files.writeString(root.resolve("blocked/whitelist.json/keep"), "nonempty directory prevents replacement");
        expect(IOException.class, () -> failed.unlink("123"));
        assert failed.contains(FIRST);

        for (String invalid : new String[]{"null", "{bad", "[]", "{\"not-a-uuid\":{}}",
                "{\"" + FIRST + "\":null}",
                "{\"" + FIRST + "\":{\"minecraftName\":\"First\",\"discordId\":\"123\"},"
                        + "\"" + SECOND + "\":{\"minecraftName\":\"Second\",\"discordId\":\"123\"}}"}) {
            Files.writeString(file, invalid);
            expect(IOException.class, () -> new DiscordWhitelist(file));
            assert Files.readString(file).equals(invalid);
        }
    }

    private static void loginChecks(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.json"), "{\"discord\":{\"enabled\":false},\"authentication\":{\"enabled\":true}}");
        DiscordWhitelist store = new DiscordWhitelist(dir.resolve("discord-whitelist.json"));
        store.link(store.issueCode(FIRST, "First"), "123");
        ChannelRegistrar registrar = stub(ChannelRegistrar.class, (name, args) -> null);
        ProxyServer proxy = stub(ProxyServer.class, (name, args) -> {
            if (name.equals("getChannelRegistrar")) return registrar;
            throw new AssertionError(name);
        });
        DiscordIntegrationVelocityPlugin plugin = new DiscordIntegrationVelocityPlugin(proxy, LOGGER, dir);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        LoginEvent allowed = login(FIRST, true);
        plugin.onLogin(allowed);
        assert allowed.getResult().isAllowed() : "Saved accounts work during Discord outages.";
        LoginEvent offline = login(FIRST, false);
        plugin.onLogin(offline);
        assert !offline.getResult().isAllowed() : "An offline UUID must never bypass authentication.";
        LoginEvent unknown = login(SECOND, true);
        plugin.onLogin(unknown);
        assert !unknown.getResult().isAllowed();
        LoginEvent alreadyDenied = login(FIRST, true);
        ComponentResult original = ComponentResult.denied(net.kyori.adventure.text.Component.text("Other plugin"));
        alreadyDenied.setResult(original);
        plugin.onLogin(alreadyDenied);
        assert alreadyDenied.getResult() == original;

        Files.writeString(dir.resolve("discord-whitelist.json"), "null");
        DiscordIntegrationVelocityPlugin broken = new DiscordIntegrationVelocityPlugin(proxy, LOGGER, dir);
        broken.onProxyInitialize(new ProxyInitializeEvent());
        LoginEvent denied = login(FIRST, true);
        broken.onLogin(denied);
        assert !denied.getResult().isAllowed();
        Files.writeString(dir.resolve("config.json"), "{\"authentication\":null}");
        DiscordIntegrationVelocityPlugin brokenConfig = new DiscordIntegrationVelocityPlugin(proxy, LOGGER, dir);
        brokenConfig.onProxyInitialize(new ProxyInitializeEvent());
        denied = login(FIRST, true);
        brokenConfig.onLogin(denied);
        assert !denied.getResult().isAllowed();

        Files.writeString(dir.resolve("config.json"), "{\"discord\":{\"enabled\":false}}");
        DiscordIntegrationVelocityPlugin legacy = new DiscordIntegrationVelocityPlugin(proxy, LOGGER, dir);
        legacy.onProxyInitialize(new ProxyInitializeEvent());
        LoginEvent oldConfig = login(SECOND, false);
        legacy.onLogin(oldConfig);
        assert oldConfig.getResult().isAllowed() : "Existing configs keep authentication disabled.";
    }

    private static void configChecks(Path dir) throws Exception {
        Files.createDirectories(dir);
        assert !PluginConfig.load(dir, LOGGER).authentication.enabled;
        for (String invalid : new String[]{"null", "{bad", "{\"authentication\":null}",
                "{\"authentication\":{\"enabled\":null}}", "{\"authentication\":{\"enabled\":\"false\"}}"}) {
            Files.writeString(dir.resolve("config.json"), invalid);
            assert PluginConfig.load(dir, LOGGER).loadFailed : invalid;
        }
    }

    private static void commandChecks(Path dir) throws Exception {
        Files.createDirectories(dir);
        DiscordIntegrationVelocityPlugin plugin = new DiscordIntegrationVelocityPlugin(null, LOGGER, dir);
        PluginConfig config = new PluginConfig();
        config.authentication.enabled = true;
        DiscordWhitelist store = new DiscordWhitelist(dir.resolve("whitelist.json"));
        var guild = stub(net.dv8tion.jda.api.entities.Guild.class, (name, args) -> switch (name) {
            case "getIdLong" -> 42L;
            case "getName" -> "Test guild";
            default -> throw new AssertionError(name);
        });
        var channel = stub(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class,
                (name, args) -> guild);
        set(plugin, "config", config);
        set(plugin, "whitelist", store);
        set(plugin, "discordChannel", channel);
        set(plugin, "jda", stub(net.dv8tion.jda.api.JDA.class,
                (name, args) -> net.dv8tion.jda.api.JDA.Status.CONNECTED));
        LoginEvent pending = login(FIRST, true);
        plugin.onLogin(pending);
        assert !pending.getResult().isAllowed();
        String shownCode = store.issueCode(FIRST, "First");
        assert ((net.kyori.adventure.text.TextComponent) pending.getResult().getReasonComponent().orElseThrow())
                .content().contains(shownCode);

        int[] registrations = {0};
        var registrationGuild = stub(net.dv8tion.jda.api.entities.Guild.class, (name, args) -> {
            assert name.equals("upsertCommand");
            var command = (net.dv8tion.jda.api.interactions.commands.build.CommandData) args[0];
            assert command.getName().equals("認証") || command.getName().equals("認証解除");
            registrations[0]++;
            return stub(net.dv8tion.jda.api.requests.restaction.CommandCreateAction.class, (n, a) -> null);
        });
        set(plugin, "discordChannel", stub(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class,
                (name, args) -> registrationGuild));
        var register = plugin.getClass().getDeclaredMethod("registerAuthenticationCommands");
        register.setAccessible(true);
        register.invoke(plugin);
        assert registrations[0] == 2;
        set(plugin, "discordChannel", channel);
        var constructor = Class.forName(DiscordIntegrationVelocityPlugin.class.getName() + "$DiscordListener")
                .getDeclaredConstructor(DiscordIntegrationVelocityPlugin.class);
        constructor.setAccessible(true);
        var listener = (net.dv8tion.jda.api.hooks.ListenerAdapter) constructor.newInstance(plugin);
        String code = store.issueCode(FIRST, "First");
        var event = new SlashEvent("認証", code, "123", null, guild, false);
        listener.onSlashCommandInteraction(event);
        assert store.contains(FIRST);
        assert event.response.contains("認証しました") && event.ephemeral;
        event = new SlashEvent("認証解除", null, "456", "123", guild, false);
        listener.onSlashCommandInteraction(event);
        assert store.contains(FIRST) && event.response.contains("権限") && event.ephemeral;
        event = new SlashEvent("認証", code, "123", null, null, false);
        listener.onSlashCommandInteraction(event);
        assert event.response.contains("利用できません") && event.ephemeral;
        var otherGuild = stub(net.dv8tion.jda.api.entities.Guild.class, (name, args) -> 99L);
        event = new SlashEvent("認証解除", null, "123", null, otherGuild, true);
        listener.onSlashCommandInteraction(event);
        assert store.contains(FIRST) && event.response.contains("利用できません");

        int[] kicks = {0};
        Player player = stub(Player.class, (name, args) -> { kicks[0]++; return null; });
        ProxyServer proxy = stub(ProxyServer.class, (name, args) -> java.util.Optional.of(player));
        set(plugin, "proxy", proxy);
        event = new SlashEvent("認証解除", null, "456", "123", guild, true);
        listener.onSlashCommandInteraction(event);
        assert !store.contains(FIRST) && kicks[0] == 1 && event.response.contains("解除しました");
        code = store.issueCode(FIRST, "First");
        listener.onSlashCommandInteraction(new SlashEvent("認証", code, "123", null, guild, false));
        event = new SlashEvent("認証解除", null, "123", null, guild, false);
        listener.onSlashCommandInteraction(event);
        assert !store.contains(FIRST) && event.response.contains("解除しました");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class SlashEvent extends net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent {
        private final String name;
        private final String code;
        private final String user;
        private final String target;
        private final net.dv8tion.jda.api.entities.Guild guild;
        private final boolean admin;
        private String response = "";
        private boolean ephemeral;

        SlashEvent(String name, String code, String user, String target,
                net.dv8tion.jda.api.entities.Guild guild, boolean admin) {
            super(stub(net.dv8tion.jda.api.JDA.class, (method, args) -> false), 0, null);
            this.name = name;
            this.code = code;
            this.user = user;
            this.target = target;
            this.guild = guild;
            this.admin = admin;
        }

        @Override public String getName() { return name; }
        @Override public boolean isFromGuild() { return guild != null; }
        @Override public net.dv8tion.jda.api.entities.Guild getGuild() { return guild; }
        @Override public net.dv8tion.jda.api.entities.Member getMember() {
            return stub(net.dv8tion.jda.api.entities.Member.class, (method, args) -> admin);
        }
        @Override public net.dv8tion.jda.api.entities.User getUser() { return user(user); }
        private net.dv8tion.jda.api.entities.User user(String id) {
            return stub(net.dv8tion.jda.api.entities.User.class, (method, args) -> id);
        }
        @Override public net.dv8tion.jda.api.interactions.commands.OptionMapping getOption(String option) {
            String value = option.equals("code") ? code : target;
            if (value == null) return null;
            return new net.dv8tion.jda.api.interactions.commands.OptionMapping(
                    net.dv8tion.jda.api.utils.data.DataObject.empty().put("type", 3).put("name", option).put("value", value),
                    new gnu.trove.map.hash.TLongObjectHashMap<>(), null, guild) {
                @Override public net.dv8tion.jda.api.entities.User getAsUser() { return user(value); }
            };
        }
        @Override public net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction reply(String content) {
            response = content;
            return action();
        }
        @Override public net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction deferReply(boolean ephemeral) {
            this.ephemeral = ephemeral;
            return action();
        }
        @SuppressWarnings("unchecked")
        private net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction action() {
            return stub(net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction.class, (method, args) -> {
                if (method.equals("setEphemeral")) { ephemeral = (boolean) args[0]; return action(); }
                if (method.equals("queue")) {
                    if (args != null && args.length > 0 && args[0] != null) {
                        var hook = stub(net.dv8tion.jda.api.interactions.InteractionHook.class, (edit, values) -> {
                            response = (String) values[0];
                            return stub(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class, (n, a) -> null);
                        });
                        ((java.util.function.Consumer<net.dv8tion.jda.api.interactions.InteractionHook>) args[0]).accept(hook);
                    }
                    return null;
                }
                throw new AssertionError(method);
            });
        }
    }

    @SuppressWarnings("removal")
    private static LoginEvent login(UUID uuid, boolean online) {
        Player player = stub(Player.class, (name, args) -> switch (name) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "First";
            case "isOnlineMode" -> online;
            default -> throw new AssertionError(name);
        });
        return new LoginEvent(player);
    }

    private interface Invocation { Object call(String name, Object[] args) throws Throwable; }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Invocation handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.call(method.getName(), args));
    }

    private static void expect(Class<? extends Exception> type, Checked action) throws Exception {
        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception)) return;
            throw exception;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private interface Checked { void run() throws Exception; }
}
