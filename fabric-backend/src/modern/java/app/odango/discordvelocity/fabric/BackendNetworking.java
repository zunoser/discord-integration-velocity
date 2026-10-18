package app.odango.discordvelocity.fabric;

import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

final class BackendNetworking {
    static void register() {
        MinecraftCompat.registerPayload();
    }

    static String world(ServerPlayer player) {
        return MinecraftCompat.world(player);
    }

    static void send(ServerPlayer player, String json) {
        ServerPlayNetworking.send(player, new BackendEventPayload(json));
    }

    record BackendEventPayload(String json) implements CustomPacketPayload {
        static final Type<BackendEventPayload> TYPE = new Type<>(MinecraftCompat.channel());
        static final StreamCodec<RegistryFriendlyByteBuf, BackendEventPayload> CODEC = StreamCodec.ofMember(
                BackendEventPayload::write, BackendEventPayload::read
        );

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBytes(json.getBytes(StandardCharsets.UTF_8));
        }

        private static BackendEventPayload read(RegistryFriendlyByteBuf buffer) {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new BackendEventPayload(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
