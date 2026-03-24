package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.network.DoubleJumpC2SPacket;
import com.komikan.agm.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 二段ジャンプクライアントハンドラ
 *
 * ── 発動条件 ──────────────────────────────────────────────────────
 *   空中 + Spaceエッジ入力 + 壁非接触 + レッジグラブ非アクティブ
 *   + 離陸後 MIN_AIRTIME_TICKS 経過（通常ジャンプとの誤発動防止）
 *
 * ── WallJump との共存 ────────────────────────────────────────────
 *   player.horizontalCollision == true の場合は WallJumpClientHandler
 *   に優先権を渡し、二段ジャンプは発動しない。
 *   どちらも同じ Space キーを使うため、排他制御が必要。
 *
 * ── 消費管理 ─────────────────────────────────────────────────────
 *   クライアント側でも canDoubleJump フラグを管理し、
 *   着地するまで二重送信を防ぐ。
 *   サーバー側の DoubleJumpHandler でも残回数を管理するため、
 *   二重チェックで不正パケットを無効化する。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class DoubleJumpClientHandler {

    /**
     * 地面を離れてからこのtick数が経過するまで二段ジャンプを抑制。
     * WallJumpClientHandler と同じ値を使い、「通常ジャンプ直後」の
     * 誤発動を防ぐ。
     */
    private static final int MIN_AIRTIME_TICKS = 4;

    private static boolean wasJumpDown    = false;
    private static boolean wasOnGround    = true;
    private static boolean canDoubleJump  = false; // 着地後にtrueになる
    private static int     airTicks       = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft   mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            reset();
            return;
        }

        boolean onGround = player.onGround();

        // 着地したら二段ジャンプを補充
        if (!wasOnGround && onGround) {
            canDoubleJump = true;
            airTicks = 0;
        }

        // 空中滞在tick計測
        if (!onGround) {
            airTicks++;
        } else {
            airTicks = 0;
        }
        wasOnGround = onGround;

        // ジャンプキーのエッジ検知
        boolean isJumpDown  = mc.options.keyJump.isDown();
        boolean justPressed = isJumpDown && !wasJumpDown;
        wasJumpDown = isJumpDown;

        if (!justPressed) return;

        // ── 発動判定 ──────────────────────────────────────────────
        boolean inAir        = !onGround;
        boolean touchingWall = player.horizontalCollision;

        // 壁接触中は WallJumpClientHandler に任せる
        if (touchingWall) return;

        // レッジグラブ中 / 登り抑制中は発動しない
        if (LedgeGrabClientHandler.isActive()) return;

        // 通常ジャンプ直後の誤発動防止
        if (airTicks < MIN_AIRTIME_TICKS) {
            AggressiveMovementMod.LOGGER.debug("二段ジャンプ抑制（滞空不足: {}tick）", airTicks);
            return;
        }

        if (inAir && canDoubleJump) {
            ModNetwork.CHANNEL.sendToServer(new DoubleJumpC2SPacket());
            canDoubleJump = false;
            AggressiveMovementMod.LOGGER.debug("二段ジャンプパケット送信 (airTicks={})", airTicks);
        }
    }

    /** ログアウト等によるリセット */
    public static void resetState() {
        reset();
    }

    private static void reset() {
        wasJumpDown   = false;
        wasOnGround   = true;
        canDoubleJump = false;
        airTicks      = 0;
    }
}
