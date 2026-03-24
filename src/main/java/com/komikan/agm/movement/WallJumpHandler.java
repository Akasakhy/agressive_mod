package com.komikan.agm.movement;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.client.effect.ParticleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 * 壁ジャンプハンドラ（サーバー側）
 *
 * ── パーティクル追加分 ─────────────────────────────────────────
 * tryWallJump() 成功時に ParticleHelper.spawnWallJump() を呼び出す。
 * 壁法線 (nx, nz) をそのまま渡してリングの向きを壁面に合わせる。
 *
 * ── 落下ダメージ緩和 ─────────────────────────────────────────────────
 * 壁ジャンプに成功したプレイヤーを walljumpedPlayers に記録する。
 * LivingFallEvent で落下距離を FALL_DISTANCE_BONUS（2.0f）分引くことで、
 * ダメージが発生する落下高さを実質 +2 ブロック延長する。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class WallJumpHandler {

    // ---- 定数 ----
    private static final double WALL_JUMP_Y           = 0.52;
    private static final double WALL_NORMAL_FORCE_MIN = 0.45;
    private static final double WALL_NORMAL_FORCE_MAX = 0.70;
    private static final double APPROACH_SPEED_MAX    = 0.70;
    private static final double LOOK_FORWARD_FORCE    = 0.25;
    private static final double PARALLEL_CARRY        = 0.55;
    private static final int    MAX_WALL_JUMPS        = 3;
    private static final int    SAME_WALL_COOLDOWN    = 30;
    private static final int    CACHE_VALID_TICKS     = 6;
    private static final float  FALL_DISTANCE_BONUS   = 2.0f;

    // ---- 状態マップ ----
    private static final Map<UUID, WallJumpState>  states   = new ConcurrentHashMap<>();
    private static final Map<UUID, CachedVelocity> velCache = new ConcurrentHashMap<>();

    private static final Set<UUID> walljumpedPlayers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =========================================================
    // パブリックAPI
    // =========================================================

    public static void tryWallJump(Player player) {
        if (player.onGround()) return;

        UUID uuid = player.getUUID();
        WallJumpState state = states.computeIfAbsent(uuid, k -> new WallJumpState());

        if (state.jumpCount >= MAX_WALL_JUMPS) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ上限に達した ({}回)", MAX_WALL_JUMPS);
            return;
        }

        Level level = player.level();
        BlockPos bp = player.blockPosition();

        double nx = 0, nz = 0;
        if (isSolid(level, bp.east()))  nx -= 1;
        if (isSolid(level, bp.west()))  nx += 1;
        if (isSolid(level, bp.south())) nz -= 1;
        if (isSolid(level, bp.north())) nz += 1;

        if (nx == 0 && nz == 0) {
            if (isSolid(level, bp.above().east()))  nx -= 1;
            if (isSolid(level, bp.above().west()))  nx += 1;
            if (isSolid(level, bp.above().south())) nz -= 1;
            if (isSolid(level, bp.above().north())) nz += 1;
        }

        if (nx == 0 && nz == 0) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ: 壁なし");
            return;
        }

        double len = Math.sqrt(nx * nx + nz * nz);
        nx /= len;
        nz /= len;

        if (state.sameCooldown > 0
                && Math.abs(state.lastNormalX - nx) < 0.1
                && Math.abs(state.lastNormalZ - nz) < 0.1) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ: 同一壁クールダウン中");
            return;
        }

        CachedVelocity cached = velCache.get(uuid);
        double prevVx = 0, prevVz = 0;
        if (cached != null && cached.age <= CACHE_VALID_TICKS) {
            prevVx = cached.vx;
            prevVz = cached.vz;
        }
        double prevHorizSpeed = Math.sqrt(prevVx * prevVx + prevVz * prevVz);

        double t           = Math.min(1.0, prevHorizSpeed / APPROACH_SPEED_MAX);
        double normalForce = WALL_NORMAL_FORCE_MIN
                + t * (WALL_NORMAL_FORCE_MAX - WALL_NORMAL_FORCE_MIN);

        double tx = -nz;
        double tz =  nx;
        double parallelComp = prevVx * tx + prevVz * tz;

        Vec3 lookDir = player.getLookAngle();
        Vec3 look    = new Vec3(lookDir.x, 0, lookDir.z);
        double lookLen = look.length();
        if (lookLen > 0.001) look = look.scale(1.0 / lookLen);

        double vx = nx * normalForce
                + tx * parallelComp * PARALLEL_CARRY
                + look.x * LOOK_FORWARD_FORCE;
        double vz = nz * normalForce
                + tz * parallelComp * PARALLEL_CARRY
                + look.z * LOOK_FORWARD_FORCE;

        player.setDeltaMovement(vx, WALL_JUMP_Y, vz);
        player.hurtMarked = true;
        player.setSprinting(true);

        WallClingHandler.clearState(uuid);
        walljumpedPlayers.add(uuid);

        // ── パーティクル: CRIT + SWEEP_ATTACK ─────────────────
        // 壁法線を渡してリングの向きを壁面に合わせる
        ParticleHelper.spawnWallJump(player, nx, nz);

        state.jumpCount++;
        state.lastNormalX  = nx;
        state.lastNormalZ  = nz;
        state.sameCooldown = SAME_WALL_COOLDOWN;

        AggressiveMovementMod.LOGGER.debug(
                "壁ジャンプ発動 ({}/{}回) normal=({}, {}) prevSpeed={} normalForce={} parallelComp={}",
                state.jumpCount, MAX_WALL_JUMPS,
                String.format("%.2f", nx), String.format("%.2f", nz),
                String.format("%.3f", prevHorizSpeed),
                String.format("%.3f", normalForce),
                String.format("%.3f", parallelComp));
    }

    // =========================================================
    // 落下ダメージ緩和
    // =========================================================

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        UUID uuid = player.getUUID();
        if (!walljumpedPlayers.remove(uuid)) return;

        float originalDistance = event.getDistance();
        float reducedDistance  = Math.max(0.0f, originalDistance - FALL_DISTANCE_BONUS);
        event.setDistance(reducedDistance);

        AggressiveMovementMod.LOGGER.debug(
                "落下ダメージ緩和: {} → {} ブロック (壁ジャンプ後)",
                String.format("%.1f", originalDistance),
                String.format("%.1f", reducedDistance));
    }

    // =========================================================
    // tick処理
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player = event.player;
        UUID   uuid   = player.getUUID();

        Vec3   vel        = player.getDeltaMovement();
        double horizX     = vel.x;
        double horizZ     = vel.z;
        double horizSpeed = Math.sqrt(horizX * horizX + horizZ * horizZ);

        CachedVelocity cached = velCache.get(uuid);
        if (cached == null) {
            velCache.put(uuid, new CachedVelocity(horizX, horizZ));
        } else {
            cached.age++;
            double cachedSpeed = Math.sqrt(cached.vx * cached.vx + cached.vz * cached.vz);
            if (horizSpeed >= cachedSpeed * 0.5 || cached.age > CACHE_VALID_TICKS) {
                velCache.put(uuid, new CachedVelocity(horizX, horizZ));
            }
        }

        WallJumpState state = states.get(uuid);
        if (state == null) return;

        if (player.onGround() && state.jumpCount > 0) {
            state.jumpCount = 0;
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ回数リセット（着地）");
        }

        if (state.sameCooldown > 0) {
            state.sameCooldown--;
        }

        if (state.jumpCount == 0 && state.sameCooldown == 0) {
            states.remove(uuid);
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.blocksMotion()) return true;
        BlockState stateAbove = level.getBlockState(pos.above());
        return !stateAbove.isAir() && stateAbove.blocksMotion();
    }

    public static int getRemainingJumps(Player player) {
        WallJumpState state = states.get(player.getUUID());
        if (state == null) return MAX_WALL_JUMPS;
        return MAX_WALL_JUMPS - state.jumpCount;
    }

    // =========================================================
    // 内部クラス
    // =========================================================

    private static class WallJumpState {
        int    jumpCount    = 0;
        double lastNormalX  = 0;
        double lastNormalZ  = 0;
        int    sameCooldown = 0;
    }

    private static class CachedVelocity {
        double vx;
        double vz;
        int    age = 0;

        CachedVelocity(double vx, double vz) {
            this.vx = vx;
            this.vz = vz;
        }
    }
}
