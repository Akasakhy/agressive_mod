package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.movement.LedgeGrabHandler;
import com.komikan.agm.network.LedgeGrabC2SPacket;
import com.komikan.agm.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * レッジグラブ（縁つかまり）クライアントハンドラ
 *
 * ── 発動条件 ──────────────────────────────────────────────────────
 *   空中 + pitch < -45° + 縁のブロックが隣接
 *   （horizontalCollision 不要: findLedge() でブロック存在を直接確認）
 *
 * ── 解除条件 ──────────────────────────────────────────────────────
 *   - 着地
 *   - しゃがみキー押下（Shift）
 *   - 画面が開いた / プレイヤー不在
 *   ※ 壁なし判定はサーバー側（hasAdjacentWall）に任せる
 *
 * ── WallJump との干渉防止 ─────────────────────────────────────────
 *   isActive() が true の間（ぶら下がり中 / CLIMB直後）は
 *   WallJumpClientHandler が壁ジャンプを無効化する。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class LedgeGrabClientHandler {

    /** CLIMB 後に壁ジャンプを抑制する tick 数 */
    private static final int CLIMB_SUPPRESS = 15;

    private static boolean isGrabbing         = false;
    private static boolean wasJumpDown        = false;
    private static int     climbSuppressTicks = 0;

    public static boolean isActive() {
        return isGrabbing || climbSuppressTicks > 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (climbSuppressTicks > 0) climbSuppressTicks--;

        Minecraft   mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            if (isGrabbing) {
                ModNetwork.CHANNEL.sendToServer(
                        new LedgeGrabC2SPacket(LedgeGrabC2SPacket.Type.RELEASE));
                isGrabbing = false;
            }
            wasJumpDown = false;
            return;
        }

        boolean inAir = !player.onGround();
        float   pitch = player.getXRot();

        // ── 自動発動 ─────────────────────────────────────────────
        if (!isGrabbing && inAir && pitch < -45.0f) {
            LedgeGrabHandler.LedgeInfo ledge = LedgeGrabHandler.findLedge(player);
            if (ledge != null) {
                ModNetwork.CHANNEL.sendToServer(
                        new LedgeGrabC2SPacket(LedgeGrabC2SPacket.Type.GRAB));
                isGrabbing  = true;
                wasJumpDown = mc.options.keyJump.isDown();
                AggressiveMovementMod.LOGGER.debug("レッジグラブ開始パケット送信");
                return;
            }
        }

        // ── ぶら下がり中の処理 ───────────────────────────────────
        if (isGrabbing) {
            // 解除条件1: しゃがみキー押下
            if (mc.options.keyShift.isDown()) {
                ModNetwork.CHANNEL.sendToServer(
                        new LedgeGrabC2SPacket(LedgeGrabC2SPacket.Type.RELEASE));
                isGrabbing  = false;
                wasJumpDown = false;
                AggressiveMovementMod.LOGGER.debug("レッジグラブ解除（しゃがみ）");
                return;
            }

            // 解除条件2: 着地
            if (player.onGround()) {
                isGrabbing  = false;
                wasJumpDown = false;
                AggressiveMovementMod.LOGGER.debug("レッジグラブ解除（着地）");
                return;
            }

            // 登り: ジャンプキーのエッジ入力
            boolean isJumpDown      = mc.options.keyJump.isDown();
            boolean jumpJustPressed = isJumpDown && !wasJumpDown;
            wasJumpDown = isJumpDown;

            if (jumpJustPressed) {
                ModNetwork.CHANNEL.sendToServer(
                        new LedgeGrabC2SPacket(LedgeGrabC2SPacket.Type.CLIMB));
                isGrabbing         = false;
                wasJumpDown        = false;
                climbSuppressTicks = CLIMB_SUPPRESS;
                AggressiveMovementMod.LOGGER.debug(
                        "レッジグラブ登りパケット送信（壁ジャンプ抑制{}tick）", CLIMB_SUPPRESS);
            }
        } else {
            wasJumpDown = mc.options.keyJump.isDown();
        }
    }

    public static void resetState() {
        isGrabbing         = false;
        wasJumpDown        = false;
        climbSuppressTicks = 0;
    }
}