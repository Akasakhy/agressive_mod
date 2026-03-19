package com.komikan.agm.network;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;


/**
 * ネットワークチャンネル管理
 * C2S / S2C パケットの登録を行う
 */
public class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AggressiveMovementMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    /**
     * 全パケットを登録
     */
    public static void register() {
        // スライディング開始パケット（クライアント→サーバー）
        CHANNEL.messageBuilder(SlideC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(SlideC2SPacket::encode)
                .decoder(SlideC2SPacket::new)
                .consumerMainThread(SlideC2SPacket::handle)
                .add();

        // 壁ジャンプパケット（クライアント→サーバー）
        CHANNEL.messageBuilder(WallJumpC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(WallJumpC2SPacket::encode)
                .decoder(WallJumpC2SPacket::new)
                .consumerMainThread(WallJumpC2SPacket::handle)
                .add();
    }
}
