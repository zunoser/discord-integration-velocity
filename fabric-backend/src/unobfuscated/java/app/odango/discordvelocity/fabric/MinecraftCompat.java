package app.odango.discordvelocity.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

final class MinecraftCompat {
    static Identifier channel() {
        return Identifier.tryParse("discordsync:events");
    }

    static String world(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    static void registerPayload() {
        PayloadTypeRegistry.clientboundPlay().register(
                BackendNetworking.BackendEventPayload.TYPE, BackendNetworking.BackendEventPayload.CODEC
        );
    }
}
