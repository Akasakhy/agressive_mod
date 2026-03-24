package com.komikan.agm.network;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * ネットワークチャンネル管理
 * C2S / S2C パケットの登録を行う
 *
 * ── パケット一覧 ────────────────────────────────────────────────
 *  0: SlideC2SPacket       スライド開始 / 終了
 *  1: WallJumpC2SPacket    壁ジャンプ
 *  2: WallClingC2SPacket   壁張り付き 開始 / 解除
 *  3: LedgeGrabC2SPacket   レッジグラブ GRAB / CLIMB / RELEASE
 *  4: DoubleJumpC2SPacket  二段ジャンプ
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
     * ※ packetId はインクリメントするため登録順を変えないこと。
     */
    public static void register() {
        // スライディング（クライアント→サーバー）
        CHANNEL.messageBuilder(SlideC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(SlideC2SPacket::encode)
                .decoder(SlideC2SPacket::new)
                .consumerMainThread(SlideC2SPacket::handle)
                .add();

        // 壁ジャンプ（クライアント→サーバー）
        CHANNEL.messageBuilder(WallJumpC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(WallJumpC2SPacket::encode)
                .decoder(WallJumpC2SPacket::new)
                .consumerMainThread(WallJumpC2SPacket::handle)
                .add();

        // 壁張り付き（クライアント→サーバー）
        CHANNEL.messageBuilder(WallClingC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(WallClingC2SPacket::encode)
                .decoder(WallClingC2SPacket::new)
                .consumerMainThread(WallClingC2SPacket::handle)
                .add();

        // レッジグラブ（クライアント→サーバー）
        CHANNEL.messageBuilder(LedgeGrabC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(LedgeGrabC2SPacket::encode)
                .decoder(LedgeGrabC2SPacket::new)
                .consumerMainThread(LedgeGrabC2SPacket::handle)
                .add();

        // 二段ジャンプ（クライアント→サーバー）
        CHANNEL.messageBuilder(DoubleJumpC2SPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(DoubleJumpC2SPacket::encode)
                .decoder(DoubleJumpC2SPacket::new)
                .consumerMainThread(DoubleJumpC2SPacket::handle)
                .add();

        AggressiveMovementMod.LOGGER.info("AGM ネットワーク登録完了 ({} パケット)", packetId);
    }
}
