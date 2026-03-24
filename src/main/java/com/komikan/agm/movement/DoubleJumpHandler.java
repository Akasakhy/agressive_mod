package com.komikan.agm.movement;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.client.effect.ParticleHelper;
import com.komikan.agm.item.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二段ジャンプハンドラ（サーバー側）
 *
 * ── 水平速度の計算方針 ────────────────────────────────────────────
 *
 * 【方向】= 二段ジャンプを押した瞬間のプレイヤーの視点水平方向（Yaw）
 *   getLookAngle() はサーバーで常に正確に取得できる。
 *   前キー   → 視点方向に飛ぶ
 *   後ろキー → 後ろを向いて押せば後ろに飛ぶ
 *   ダッシュ後に左を向いて二段ジャンプ → 左に飛ぶ
 *   ※ 視点の向きがそのまま方向になるので直感的
 *
 * 【大きさ】= launchSpeed（地面を蹴った瞬間の水平速度キャッシュ）
 *   空中での drag 減衰を無視し、ダッシュの勢いをそのまま引き継ぐ。
 *   静止ジャンプなら launchSpeed ≈ 0 → DEAD_ZONE 判定で垂直ジャンプ。
 *
 * 【移動キー未入力の方向転換について】
 *   プレイヤーが「右を向きながらダッシュジャンプ → 左を向いて二段ジャンプ」
 *   という操作をすると、getLookAngle() は左向きなのでそちらに飛ぶ。
 *   速度の大きさはダッシュ速度なのでスピードも維持される。
 *
 * ── 落下ダメージ緩和 ─────────────────────────────────────────────
 * 壁ジャンプと同方式。FALL_DISTANCE_BONUS = 4.0f。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class DoubleJumpHandler {

    // ---- 定数 ----
    private static final double JUMP_Y_BASE    = 0.58;
    private static final double JUMP_Y_FALLING = 0.68;

    /** 離陸速度の引き継ぎ割合 */
    private static final double MOMENTUM_CARRY = 0.95;

    /**
     * 離陸水平速度のデッドゾーン（blocks/tick）。
     * これ未満なら「静止ジャンプ」とみなして純垂直ジャンプにする。
     * スニーク ≈ 0.065 / 歩き ≈ 0.13 / ダッシュ ≈ 0.26
     */
    private static final double DEAD_ZONE      = 0.08;

    private static final float FALL_DISTANCE_BONUS = 4.0f;
    private static final int   MAX_JUMPS           = 1;

    // ---- 状態マップ ----
    private static final Map<UUID, Integer>  jumpsRemaining     = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean>  wasOnGround        = new ConcurrentHashMap<>();
    /** 離陸瞬間の水平速度キャッシュ（大きさのみ使用）*/
    private static final Map<UUID, Double>   launchSpeed        = new ConcurrentHashMap<>();
    private static final Set<UUID>           doublejumpedPlayers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =========================================================
    // パブリックAPI
    // =========================================================

    public static void tryDoubleJump(Player player) {
        if (!hasFeather(player)) {
            AggressiveMovementMod.LOGGER.debug("二段ジャンプ: 羽なし");
            return;
        }
        if (player.onGround()) return;

        UUID uuid = player.getUUID();
        int remaining = jumpsRemaining.getOrDefault(uuid, 0);
        if (remaining <= 0) {
            AggressiveMovementMod.LOGGER.debug("二段ジャンプ: 残回数なし");
            return;
        }

        Vec3 cur = player.getDeltaMovement();

        // Y速度: 落下中は高め補正
        double jumpY = (cur.y < -0.1) ? JUMP_Y_FALLING : JUMP_Y_BASE;

        // 離陸時の水平速度（大きさ）
        double speed = launchSpeed.getOrDefault(uuid, 0.0);

        double vx, vz;

        if (speed < DEAD_ZONE) {
            // 静止ジャンプ → 純垂直
            vx = 0.0;
            vz = 0.0;
            AggressiveMovementMod.LOGGER.debug(
                    "二段ジャンプ: 垂直ジャンプ (launchSpeed={})",
                    String.format("%.3f", speed));
        } else {
            // 方向: 二段ジャンプを押した瞬間の視点水平方向 (Yaw)
            // getLookAngle() はサーバーで常に正確。カメラが向いている方向がそのまま方向になる。
            Vec3 look = player.getLookAngle();
            // 水平成分だけを取り出して正規化
            double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);
            double dirX, dirZ;
            if (lookHorizLen > 0.001) {
                dirX = look.x / lookHorizLen;
                dirZ = look.z / lookHorizLen;
            } else {
                // 真上や真下を向いている極端なケース: 現在速度方向にフォールバック
                double curHoriz = Math.sqrt(cur.x * cur.x + cur.z * cur.z);
                if (curHoriz > 0.001) {
                    dirX = cur.x / curHoriz;
                    dirZ = cur.z / curHoriz;
                } else {
                    dirX = 0.0;
                    dirZ = 0.0;
                }
            }

            double targetSpeed = speed * MOMENTUM_CARRY;
            vx = dirX * targetSpeed;
            vz = dirZ * targetSpeed;

            AggressiveMovementMod.LOGGER.debug(
                    "二段ジャンプ: lookDir=({}, {}) launchSpeed={} targetSpeed={}",
                    String.format("%.3f", dirX), String.format("%.3f", dirZ),
                    String.format("%.3f", speed),
                    String.format("%.3f", targetSpeed));
        }

        player.setDeltaMovement(vx, jumpY, vz);
        player.hurtMarked = true;
        player.setSprinting(speed >= DEAD_ZONE);

        jumpsRemaining.put(uuid, remaining - 1);
        doublejumpedPlayers.add(uuid);

        ParticleHelper.spawnDoubleJump(player);

        AggressiveMovementMod.LOGGER.debug(
                "二段ジャンプ発動 jumpY={} vx={} vz={}: {}",
                String.format("%.2f", jumpY),
                String.format("%.3f", vx),
                String.format("%.3f", vz),
                player.getName().getString());
    }

    // =========================================================
    // 落下ダメージ緩和
    // =========================================================

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        if (!doublejumpedPlayers.remove(player.getUUID())) return;

        float originalDistance = event.getDistance();
        float reducedDistance  = Math.max(0.0f, originalDistance - FALL_DISTANCE_BONUS);
        event.setDistance(reducedDistance);

        AggressiveMovementMod.LOGGER.debug(
                "落下ダメージ緩和 (二段ジャンプ): {} → {} ブロック",
                String.format("%.1f", originalDistance),
                String.format("%.1f", reducedDistance));
    }

    // =========================================================
    // 羽の所持チェック
    // =========================================================

    public static boolean hasFeather(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ModItems.DOUBLE_JUMP_FEATHER.get())) {
                return true;
            }
        }
        return false;
    }

    public static boolean canDoubleJump(Player player) {
        return hasFeather(player)
                && jumpsRemaining.getOrDefault(player.getUUID(), 0) > 0;
    }

    public static void clearState(UUID uuid) {
        jumpsRemaining.remove(uuid);
        wasOnGround.remove(uuid);
        launchSpeed.remove(uuid);
        doublejumpedPlayers.remove(uuid);
    }

    // =========================================================
    // tick処理: 離陸を検知して速度をキャッシュ
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player  player   = event.player;
        UUID    uuid     = player.getUUID();
        boolean onGround = player.onGround();

        Boolean prev = wasOnGround.get(uuid);
        if (prev == null) {
            wasOnGround.put(uuid, onGround);
            if (onGround) jumpsRemaining.put(uuid, MAX_JUMPS);
            return;
        }

        if (prev && !onGround) {
            // 地面 → 空中: 離陸瞬間の水平速度の「大きさ」だけキャッシュ
            Vec3 vel = player.getDeltaMovement();
            double spd = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            launchSpeed.put(uuid, spd);
            AggressiveMovementMod.LOGGER.debug(
                    "離陸速度キャッシュ: speed={}", String.format("%.3f", spd));
        }

        if (!prev && onGround) {
            jumpsRemaining.put(uuid, MAX_JUMPS);
            launchSpeed.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("二段ジャンプ残回数リセット（着地）");
        }

        wasOnGround.put(uuid, onGround);
    }
}