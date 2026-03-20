package com.komikan.agm.network;

import com.komikan.agm.movement.WallClingHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 壁張り付きパケット（クライアント → サーバー）
 * release=false: 張り付き開始（スニーク押下 + 空中 + 壁接触）
 * release=true:  張り付き解除（スニーク離し or 着地）
 */
public class WallClingC2SPacket {

    private final boolean release;

    public WallClingC2SPacket(boolean release) {
        this.release = release;
    }

    public WallClingC2SPacket(FriendlyByteBuf buf) {
        this.release = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(release);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            if (release) {
                WallClingHandler.releaseKey(player);
            } else {
                WallClingHandler.tryStartCling(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
