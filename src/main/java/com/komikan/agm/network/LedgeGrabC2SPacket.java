package com.komikan.agm.network;

import com.komikan.agm.movement.LedgeGrabHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * レッジグラブパケット（クライアント → サーバー）
 *
 * GRAB   : 縁を掴んだ（自動発動）
 * CLIMB  : ジャンプキーで登り開始
 * RELEASE: 解除（着地 / 壁なし / 画面開放）
 */
public class LedgeGrabC2SPacket {

    public enum Type { GRAB, CLIMB, RELEASE }

    private final Type type;

    public LedgeGrabC2SPacket(Type type) {
        this.type = type;
    }

    /** デコード */
    public LedgeGrabC2SPacket(FriendlyByteBuf buf) {
        this.type = Type.values()[buf.readByte()];
    }

    /** エンコード */
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(type.ordinal());
    }

    /** サーバー側処理 */
    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            switch (type) {
                case GRAB    -> LedgeGrabHandler.tryStartGrab(player);
                case CLIMB   -> LedgeGrabHandler.climb(player);
                case RELEASE -> LedgeGrabHandler.release(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
