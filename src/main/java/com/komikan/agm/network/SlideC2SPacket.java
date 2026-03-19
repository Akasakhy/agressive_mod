package com.komikan.agm.network;

import com.komikan.agm.movement.SlideHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * スライディングパケット（クライアント → サーバー）
 * release=false: スライド開始（キー押下）
 * release=true:  スライド終了リクエスト（キー離し）
 */
public class SlideC2SPacket {

    private final boolean release;

    public SlideC2SPacket(boolean release) {
        this.release = release;
    }

    /** デコード */
    public SlideC2SPacket(FriendlyByteBuf buf) {
        this.release = buf.readBoolean();
    }

    /** エンコード */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(release);
    }

    /** サーバー側処理 */
    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            if (release) {
                // キー離し → 終了フェーズへ移行
                SlideHandler.releaseKey(player);
            } else {
                // キー押下 → スライド開始
                SlideHandler.tryStartSlide(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
