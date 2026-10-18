package app.odango.discordvelocity.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

public final class BackendCompatibilityTest {
    public static void main(String[] args) {
        String name = "player\"\\";
        String message = "死亡\n\t\b\f\r\u0001 with \"quotes\" and \\ and 😀";
        String uuid = "00000000-0000-0000-0000-000000000001";
        String json = DiscordIntegrationFabricBackend.deathJson(
                name, uuid, message, "minecraft:the_nether", -123, -64, 456
        );
        // Velocity decodes the entire plugin message as UTF-8 JSON, without a string length prefix.
        byte[] wire = json.getBytes(StandardCharsets.UTF_8);
        JsonObject event = JsonParser.parseString(new String(wire, StandardCharsets.UTF_8)).getAsJsonObject();
        assert event.size() == 8 : event;
        assert event.get("type").getAsString().equals("death");
        assert event.get("player").getAsString().equals(name);
        assert event.get("uuid").getAsString().equals(uuid);
        assert event.get("deathMessage").getAsString().equals(message);
        assert event.get("world").getAsString().equals("minecraft:the_nether");
        assert event.get("x").getAsInt() == -123;
        assert event.get("y").getAsInt() == -64;
        assert event.get("z").getAsInt() == 456;
        System.out.println("Backend JSON protocol compatibility passed.");
    }
}
