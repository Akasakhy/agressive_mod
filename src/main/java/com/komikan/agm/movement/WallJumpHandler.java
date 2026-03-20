package com.komikan.agm.movement;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 壁ジャンプハンドラ（サーバー側）
 *
 * ── 速度キャッシュの仕組み ──────────────────────────────────────────
 * Minecraftは壁衝突時に法線方向の速度成分を即座にゼロにする。
 * そのためパケットが届く頃には getDeltaMovement() ≈ 0 になっており、
 * リアルタイム速度から接近速度を測定することができない。
 *
 * 対策: サーバーtick毎に「前tickの水平速度ベクトル」をキャッシュし、
 *       壁ジャンプ処理ではキャッシュ値を参照する。
 *       壁衝突で速度が大きく落ちた直後（CACHE_VALID_TICKS以内）は
 *       キャッシュを上書きせず、衝突前の速度を温存する。
 *
 * ── 法線方向反発力 ───────────────────────────────────────────────────
 * キャッシュした水平速度の大きさを [0, APPROACH_SPEED_MAX] で正規化し、
 * [WALL_NORMAL_FORCE_MIN, WALL_NORMAL_FORCE_MAX] へ線形マップ。
 * MIN を十分大きくすることで、立ち止まり壁ジャンプでも横に飛べる。
 *
 * ── 壁平行成分の引き継ぎ ─────────────────────────────────────────────
 * スライディングや走り込みで壁と平行方向に速度を持っている場合、
 * その成分を PARALLEL_CARRY 割合だけ壁ジャンプ後速度に加算する。
 * これにより「スライドしながら壁を蹴って横へ飛ぶ」動きが自然になる。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class WallJumpHandler {

    // ---- 定数 ----
    /** 上昇速度（固定） */
    private static final double WALL_JUMP_Y           = 0.52;

    /** 法線方向反発力の最小値（接近速度ほぼ0のとき）。垂直ジャンプにならない程度に設定 */
    private static final double WALL_NORMAL_FORCE_MIN = 0.45;

    /** 法線方向反発力の最大値（高速接近時） */
    private static final double WALL_NORMAL_FORCE_MAX = 0.70;

    /**
     * WALL_NORMAL_FORCE_MAX に達する水平速度の基準値。
     * スライディング最高速 (0.55 * 1.4 ≈ 0.77) に合わせる。
     */
    private static final double APPROACH_SPEED_MAX    = 0.70;

    /** 視線前方方向への加算力（固定） */
    private static final double LOOK_FORWARD_FORCE    = 0.25;

    /**
     * 壁と平行な速度成分を引き継ぐ割合。
     * 1.0 = 完全保持、0.0 = 全カット。
     * スライドの横流れ慣性を壁ジャンプ後も活かす。
     */
    private static final double PARALLEL_CARRY        = 0.55;

    /** 最大連続壁ジャンプ回数 */
    private static final int    MAX_WALL_JUMPS        = 3;

    /** 同一壁の再使用禁止tick数 */
    private static final int    SAME_WALL_COOLDOWN    = 30;

    /**
     * 速度キャッシュの有効tick数。
     * 壁に当たってから何tick以内のキャッシュを使うか。
     * 大きすぎると古い速度を参照してしまうため小さめに設定。
     */
    private static final int    CACHE_VALID_TICKS     = 6;

    // ---- 状態マップ ----
    private static final Map<UUID, WallJumpState>  states   = new ConcurrentHashMap<>();
    /** 衝突前の水平速度キャッシュ */
    private static final Map<UUID, CachedVelocity> velCache = new ConcurrentHashMap<>();

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

        // 壁法線を計算（4方向 + 1段上）
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

        // 法線を正規化
        double len = Math.sqrt(nx * nx + nz * nz);
        nx /= len;
        nz /= len;

        // 同一壁クールダウンチェック
        if (state.sameCooldown > 0
                && Math.abs(state.lastNormalX - nx) < 0.1
                && Math.abs(state.lastNormalZ - nz) < 0.1) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ: 同一壁クールダウン中");
            return;
        }

        // ── キャッシュから衝突前速度を取得 ──────────────────────────
        // 壁衝突によってゼロになる前の速度を参照する
        CachedVelocity cached = velCache.get(uuid);
        double prevVx = 0, prevVz = 0;
        if (cached != null && cached.age <= CACHE_VALID_TICKS) {
            prevVx = cached.vx;
            prevVz = cached.vz;
        }
        double prevHorizSpeed = Math.sqrt(prevVx * prevVx + prevVz * prevVz);

        // ── 法線方向反発力を衝突前速度でスケール ────────────────────
        double t           = Math.min(1.0, prevHorizSpeed / APPROACH_SPEED_MAX);
        double normalForce = WALL_NORMAL_FORCE_MIN
                + t * (WALL_NORMAL_FORCE_MAX - WALL_NORMAL_FORCE_MIN);

        // ── 壁平行成分の引き継ぎ ────────────────────────────────────
        // 壁法線 (nx, nz) に対して垂直な接線ベクトル (-nz, nx)
        double tx = -nz;
        double tz =  nx;
        // キャッシュ速度の接線方向成分（壁沿いに移動していた慣性）
        double parallelComp = prevVx * tx + prevVz * tz;

        // ── 視線前方方向 ─────────────────────────────────────────────
        Vec3 lookDir = player.getLookAngle();
        Vec3 look    = new Vec3(lookDir.x, 0, lookDir.z);
        double lookLen = look.length();
        if (lookLen > 0.001) look = look.scale(1.0 / lookLen);

        // ── 最終速度 = 法線反発 + 平行慣性引き継ぎ + 視線加算 ────────
        double vx = nx * normalForce
                + tx * parallelComp * PARALLEL_CARRY
                + look.x * LOOK_FORWARD_FORCE;
        double vz = nz * normalForce
                + tz * parallelComp * PARALLEL_CARRY
                + look.z * LOOK_FORWARD_FORCE;

        player.setDeltaMovement(vx, WALL_JUMP_Y, vz);
        player.hurtMarked = true;
        player.setSprinting(true);

        // 状態更新
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
    // tick処理
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player = event.player;
        UUID   uuid   = player.getUUID();

        // ── 速度キャッシュ更新 ───────────────────────────────────────
        // 壁衝突で速度が落ちた直後はキャッシュを上書きしない（衝突前速度を温存）
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
            // 現在速度がキャッシュの半分以上なら通常更新、
            // 壁衝突で急減速している場合はキャッシュを温存してageだけ進める
            if (horizSpeed >= cachedSpeed * 0.5 || cached.age > CACHE_VALID_TICKS) {
                velCache.put(uuid, new CachedVelocity(horizX, horizZ));
            }
        }

        // ── 壁ジャンプ状態の管理 ────────────────────────────────────
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

    /** 衝突前の水平速度キャッシュ */
    private static class CachedVelocity {
        double vx;
        double vz;
        /** キャッシュ生成からのtick数 */
        int    age = 0;

        CachedVelocity(double vx, double vz) {
            this.vx = vx;
            this.vz = vz;
        }
    }
}