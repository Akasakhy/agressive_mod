package com.komikan.agm.network;

import com.komikan.agm.movement.DoubleJumpHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 二段ジャンプパケット（クライアント → サーバー）
 * クライアントが「空中 + Space + 壁非接触」を検知したらサーバーに通知。
 * ペイロードなし（1パケット = 1リクエスト）。
 */
public class DoubleJumpC2SPacket {

    public DoubleJumpC2SPacket() {}

    /** デコード（ペイロードなし） */
    public DoubleJumpC2SPacket(FriendlyByteBuf buf) {}

    /** エンコード（ペイロードなし） */
    public void encode(FriendlyByteBuf buf) {}

    /** サーバー側処理 */
    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                DoubleJumpHandler.tryDoubleJump(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
