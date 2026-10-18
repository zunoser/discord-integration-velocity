package app.odango.discordvelocity.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiscordIntegrationFabricBackend implements DedicatedServerModInitializer {
    public static final String MOD_ID = "discordintegrationfabricbackend";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {
        BackendNetworking.register();
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onAfterDeath);
        LOGGER.info("Discord Integration Fabric Backend loaded.");
    }

    private void onAfterDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        BlockPos position = player.blockPosition();
        String json = deathJson(
                player.getName().getString(),
                player.getUUID().toString(),
                damageSource.getLocalizedDeathMessage(player).getString(),
                BackendNetworking.world(player),
                position.getX(),
                position.getY(),
                position.getZ()
        );

        BackendNetworking.send(player, json);
    }

    static String deathJson(String player, String uuid, String deathMessage, String world, int x, int y, int z) {
        return "{\"type\":\"death\""
                + ",\"player\":\"" + escapeJson(player) + "\""
                + ",\"uuid\":\"" + escapeJson(uuid) + "\""
                + ",\"deathMessage\":\"" + escapeJson(deathMessage) + "\""
                + ",\"world\":\"" + escapeJson(world) + "\""
                + ",\"x\":" + x
                + ",\"y\":" + y
                + ",\"z\":" + z
                + "}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
