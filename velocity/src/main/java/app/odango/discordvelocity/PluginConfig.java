package app.odango.discordvelocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
                PluginConfig config = GSON.fromJson(reader, PluginConfig.class);
                return config == null ? new PluginConfig() : config;
            }
        } catch (IOException exception) {
            logger.error("Failed to load config.json. Using defaults.", exception);
            return new PluginConfig();
        }
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
