package com.komikan.agm.client.effect;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * パーティクルヘルパー（サーバー側）
 *
 * ServerLevel#sendParticles を使って全クライアントにパーティクルをブロードキャストする。
 *
 * ┌─────────────────┬──────────────────────────────────────────┐
 * │ 動作             │ パーティクル                              │
 * ├─────────────────┼──────────────────────────────────────────┤
 * │ スライド開始     │ CLOUD (足元リング)                        │
 * │ スライド持続     │ CAMPFIRE_SIGNAL_SMOKE (後方トレイル)      │
 * │ 壁ジャンプ       │ CRIT (リング) + SWEEP_ATTACK (中心)       │
 * │ 二段ジャンプ     │ ENCHANT (放射) + END_ROD (上昇)           │
 * │ コンボ開始       │ FIREWORK (バースト)                       │
 * └─────────────────┴──────────────────────────────────────────┘
 */
public class ParticleHelper {

    // =========================================================
    // スライディング
    // =========================================================

    /**
     * スライド開始時 — 足元 CLOUD リング (8個)
     * 「地面を蹴り上げた」視覚的フィードバック。
     */
    public static void spawnSlideStart(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;

        double x = player.getX();
        double y = player.getY() + 0.05;
        double z = player.getZ();

        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 / 8) * i;
            double ox    = Math.cos(angle) * 0.45;
            double oz    = Math.sin(angle) * 0.45;
            level.sendParticles(
                    ParticleTypes.CLOUD,
                    x + ox, y, z + oz,
                    1,           // count
                    0.0, 0.05, 0.0, // offset
                    0.03         // speed (= 拡散速度)
            );
        }
    }

    /**
     * スライド持続中 — 後方へ CAMPFIRE_SIGNAL_SMOKE トレイル
     * 毎3tick呼び出し想定。煙が進行方向逆に流れるよう速度ベクトルを逆算。
     *
     * @param player スライド中プレイヤー
     */
    public static void spawnSlideTick(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;

        Vec3 vel = player.getDeltaMovement();

        // 進行方向の逆 × 2ブロック後方
        double tx = player.getX() - vel.x * 2.5;
        double ty = player.getY() + 0.1;
        double tz = player.getZ() - vel.z * 2.5;

        level.sendParticles(
                ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                tx, ty, tz,
                1,
                0.08, 0.04, 0.08,
                0.005
        );
    }

    // =========================================================
    // 壁ジャンプ
    // =========================================================

    /**
     * 壁ジャンプ発動時 — CRIT リング (12個) + SWEEP_ATTACK (中心)
     * 「壁を蹴った衝撃」表現。リングは腰の高さに展開。
     *
     * @param player 壁ジャンプしたプレイヤー
     * @param wallNX 壁法線X（ジャンプ方向の反転元）
     * @param wallNZ 壁法線Z
     */
    public static void spawnWallJump(Player player, double wallNX, double wallNZ) {
        if (!(player.level() instanceof ServerLevel level)) return;

        // 壁面位置（プレイヤーの逆方向 = 壁の面）
        double wx = player.getX() - wallNX * 0.6;
        double wy = player.getY() + 1.0;
        double wz = player.getZ() - wallNZ * 0.6;

        // CRIT — 壁面を中心にしたリング
        for (int i = 0; i < 6; i++) {
            double angle = (Math.PI * 2.0 / 12) * i;
            // 壁の法線が X 寄りなら YZ 面でリング、Z 寄りなら XY 面でリング
            double ox, oy, oz;
            if (Math.abs(wallNX) > Math.abs(wallNZ)) {
                ox = 0;
                oy = Math.cos(angle) * 0.55;
                oz = Math.sin(angle) * 0.55;
            } else {
                ox = Math.cos(angle) * 0.55;
                oy = Math.sin(angle) * 0.55;
                oz = 0;
            }
            level.sendParticles(
                    ParticleTypes.CRIT,
                    wx + ox, wy + oy, wz + oz,
                    1,
                    0.0, 0.0, 0.0,
                    0.04
            );
        }

        // SWEEP_ATTACK — 壁面中心に集中
        level.sendParticles(
                ParticleTypes.SWEEP_ATTACK,
                wx, wy, wz,
                6,
                0.25, 0.25, 0.25,
                0.08
        );
    }

    // =========================================================
    // 二段ジャンプ
    // =========================================================

    /**
     * 二段ジャンプ発動時 — ENCHANT 放射 (16個) + END_ROD 上昇 (10個)
     * 魔法的・浮遊感のある演出。
     *
     * @param player 二段ジャンプしたプレイヤー
     */
    public static void spawnDoubleJump(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;

        double x = player.getX();
        double y = player.getY() + 0.6;
        double z = player.getZ();

        // ENCHANT — 水平放射リング
        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 / 16) * i;
            double ox    = Math.cos(angle) * 0.65;
            double oz    = Math.sin(angle) * 0.65;
            level.sendParticles(
                    ParticleTypes.ENCHANT,
                    x + ox, y, z + oz,
                    1,
                    0.0, 0.15, 0.0,
                    0.06
            );
        }

        // END_ROD — 上方向バースト（浮遊感）
        level.sendParticles(
                ParticleTypes.END_ROD,
                x, y, z,
                10,
                0.3, 0.05, 0.3,
                0.12
        );
    }

}
