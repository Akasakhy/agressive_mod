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
 * ── 地面ジャンプ直後の誤発動防止 ─────────────────────────────────
 * 地面を離れてから MIN_AIRTIME_TICKS 経過するまで壁ジャンプを無効化する。
 * こうすることで「地面から壁に向かってジャンプ」した瞬間の誤発動を防ぐ。
 *
 * ── レッジグラブとの干渉防止 ─────────────────────────────────────
 * LedgeGrabClientHandler.isActive() が true の間は壁ジャンプを無効化する。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class WallJumpClientHandler {

    /**
     * 地面を離れてからこのtick数が経過するまで壁ジャンプを無効化する。
     * 値が小さすぎると通常ジャンプ時に誤発動し、大きすぎると反応が遅れる。
     * 6tick（約0.3秒）で「意図的に壁へ向かった」と判断できる。
     */
    private static final int MIN_AIRTIME_TICKS = 4;

    private static boolean wasJumpDown  = false;
    private static boolean wasOnGround  = true;
    private static int     airTicks     = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft   mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            wasJumpDown = false;
            wasOnGround = true;
            airTicks    = 0;
            return;
        }

        // 空中滞在tick数を追跡
        boolean onGround = player.onGround();
        if (onGround) {
            airTicks    = 0;
            wasOnGround = true;
        } else {
            if (wasOnGround) {
                // 地面を離れた瞬間にリセット
                airTicks = 0;
            }
            airTicks++;
            wasOnGround = false;
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

        boolean inAir        = !onGround;
        boolean touchingWall = player.horizontalCollision;

        // 地面を離れて MIN_AIRTIME_TICKS 以上経過していないと壁ジャンプ不可
        if (inAir && touchingWall && airTicks >= MIN_AIRTIME_TICKS) {
            ModNetwork.CHANNEL.sendToServer(new WallJumpC2SPacket());
            AggressiveMovementMod.LOGGER.debug("壁ジャンプパケット送信 (airTicks={})", airTicks);
        } else if (inAir && touchingWall) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ抑制（滞空不足: {}tick）", airTicks);
        }
    }
}