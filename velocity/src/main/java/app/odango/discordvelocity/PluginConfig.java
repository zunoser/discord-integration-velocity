package app.odango.discordvelocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

public final class PluginConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Discord discord = new Discord();
    public Minecraft minecraft = new Minecraft();
    public Embeds embeds = new Embeds();
    public Authentication authentication = new Authentication();
    transient boolean loadFailed;

    static PluginConfig load(Path dataDirectory, Logger logger) {
        Path configPath = dataDirectory.resolve("config.json");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configPath)) {
                PluginConfig defaults = new PluginConfig();
                try (Writer writer = Files.newBufferedWriter(configPath)) {
                    GSON.toJson(defaults, writer);
                }
                logger.warn("Created default config at {}. Set the Discord token and channelId before enabling Discord sync.", configPath);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(configPath)) {
                var json = JsonParser.parseReader(reader);
                if (json.isJsonObject() && json.getAsJsonObject().has("authentication")) {
                    var auth = json.getAsJsonObject().get("authentication");
                    if (!auth.isJsonObject() || (auth.getAsJsonObject().has("enabled")
                            && (!auth.getAsJsonObject().get("enabled").isJsonPrimitive()
                            || !auth.getAsJsonObject().get("enabled").getAsJsonPrimitive().isBoolean()))) {
                        throw new JsonParseException("authentication.enabled must be a boolean.");
                    }
                }
                PluginConfig config = GSON.fromJson(json, PluginConfig.class);
                if (config == null || config.discord == null || config.minecraft == null
                        || config.embeds == null || config.authentication == null
                        || config.discord.token == null || config.discord.channelId == null
                        || config.discord.status == null || config.minecraft.syncedServers == null) {
                    throw new JsonParseException("Required config objects/values must not be null.");
                }
                return config;
            }
        } catch (IOException | JsonParseException exception) {
            logger.error("Failed to load config.json. Player logins will be denied until fixed and restarted.", exception);
            PluginConfig failed = new PluginConfig();
            failed.loadFailed = true;
            return failed;
        }
    }

    public static final class Authentication {
        public boolean enabled = false;
    }

    public static final class Discord {
        public boolean enabled = true;
        public String token = "";
        public String channelId = "";
        public String status = "Minecraft chat";
    }

    public static final class Minecraft {
        public boolean broadcastDiscordMessages = true;
        public boolean broadcastMinecraftChatToOtherServers = true;
        public List<String> syncedServers = new ArrayList<>(List.of("*"));

        boolean shouldSyncServer(String serverName) {
            return syncedServers.contains("*") || syncedServers.contains(serverName);
        }
    }

    public static final class Embeds {
        public boolean joinLeave = true;
        public boolean serverSwitch = true;
        public boolean death = true;
        public int joinColor = 0x43B581;
        public int leaveColor = 0xF04747;
        public int switchColor = 0xFAA61A;
        public int deathColor = 0x992D22;
    }
}
