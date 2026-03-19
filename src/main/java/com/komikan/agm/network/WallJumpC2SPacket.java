package com.komikan.agm.network;

import com.komikan.agm.movement.WallJumpHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 壁ジャンプパケット（クライアント → サーバー）
 * クライアントが壁接触+ジャンプキーを検知したらサーバーに通知
 */
public class WallJumpC2SPacket {

    public WallJumpC2SPacket() {}

    public WallJumpC2SPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                WallJumpHandler.tryWallJump(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
