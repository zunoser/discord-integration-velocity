package app.odango.discordvelocity.fabric;

import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class BackendNetworking {
    private static final ResourceLocation CHANNEL = ResourceLocation.tryParse("discordsync:events");

    static void register() {
        // Before 1.20.5, outgoing plugin messages have no payload type registry.
    }

    static String world(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    static void send(ServerPlayer player, String json) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        buffer.writeBytes(json.getBytes(StandardCharsets.UTF_8));
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }
}
