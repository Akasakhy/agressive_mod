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
 * 条件: 空中 + 壁接触 + Spaceキーのエッジ入力
 *
 * ── レッジグラブとの干渉防止 ─────────────────────────────────────
 * LedgeGrabClientHandler.isActive() が true の間は壁ジャンプを無効化する。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class WallJumpClientHandler {

    private static boolean wasJumpDown = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft   mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            wasJumpDown = false;
            return;
        }

        boolean isJumpDown  = mc.options.keyJump.isDown();
        boolean justPressed = isJumpDown && !wasJumpDown;
        wasJumpDown = isJumpDown;

        if (!justPressed) return;

        // レッジグラブ中 / 登り抑制中は壁ジャンプを無効化
        if (LedgeGrabClientHandler.isActive()) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ抑制（レッジグラブアクティブ）");
            return;
        }

        boolean inAir        = !player.onGround();
        boolean touchingWall = player.horizontalCollision;

        if (inAir && touchingWall) {
            ModNetwork.CHANNEL.sendToServer(new WallJumpC2SPacket());
            AggressiveMovementMod.LOGGER.debug("壁ジャンプパケット送信");
        }
    }
}
