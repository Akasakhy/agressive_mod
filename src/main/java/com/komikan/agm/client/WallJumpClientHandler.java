package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.network.ModNetwork;
import com.komikan.agm.network.WallJumpC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 壁ジャンプクライアントハンドラ
 *
 * 条件: 空中（!onGround）+ 壁に接触（horizontalCollision）+ Spaceキー押下
 * エッジ検知で1回のみパケット送信
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class WallJumpClientHandler {

    /** 前tickのジャンプキー状態（エッジ検知用） */
    private static boolean wasJumpDown = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            wasJumpDown = false;
            return;
        }

        // バニラのジャンプキー（Space）の押下状態を取得
        boolean isJumpDown = mc.options.keyJump.isDown();
        boolean justPressed = isJumpDown && !wasJumpDown;
        wasJumpDown = isJumpDown;

        if (!justPressed) return;

        // 壁ジャンプ条件: 空中 + 水平方向に何かに衝突
        boolean inAir           = !player.onGround();
        boolean touchingWall    = player.horizontalCollision;

        if (inAir && touchingWall) {
            ModNetwork.CHANNEL.sendToServer(new WallJumpC2SPacket());
            AggressiveMovementMod.LOGGER.debug("壁ジャンプパケット送信");
        }
    }
}
