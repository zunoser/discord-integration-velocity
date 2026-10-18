package app.odango.discordvelocity.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class MinecraftCompat {
    static ResourceLocation channel() {
        return ResourceLocation.tryParse("discordsync:events");
    }

    static String world(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    static void registerPayload() {
        PayloadTypeRegistry.playS2C().register(
                BackendNetworking.BackendEventPayload.TYPE, BackendNetworking.BackendEventPayload.CODEC
        );
    }
}
