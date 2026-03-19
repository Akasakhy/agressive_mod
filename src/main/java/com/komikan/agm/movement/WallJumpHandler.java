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
 * 仕様:
 * - 空中 + 壁接触の状態でジャンプキー → 壁を蹴って反発
 * - 上昇速度: 0.52（通常 0.42 より高め）
 * - 水平: 壁法線方向 × 0.5 + 視線方向 × 0.3
 * - 最大3回連続、着地でリセット
 * - 直前と同じ壁方向は 30tick（1.5秒）クールダウン
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class WallJumpHandler {

    // ---- 定数 ----
    /** 上昇速度 */
    private static final double WALL_JUMP_Y       = 0.52;
    /** 壁法線方向への水平力 */
    private static final double WALL_NORMAL_FORCE = 0.5;
    /** 視線方向への加算力 */
    private static final double LOOK_FORWARD_FORCE = 0.3;
    /** 最大連続壁ジャンプ回数 */
    private static final int    MAX_WALL_JUMPS    = 3;
    /** 同一壁の再使用禁止tick数 */
    private static final int    SAME_WALL_COOLDOWN = 30;

    // ---- 状態マップ ----
    private static final Map<UUID, WallJumpState> states = new ConcurrentHashMap<>();

    // =========================================================
    // パブリックAPI
    // =========================================================

    /**
     * 壁ジャンプを試みる（パケット受信時に呼び出し）
     */
    public static void tryWallJump(Player player) {
        if (player.onGround()) return; // 地面では使えない

        UUID uuid = player.getUUID();
        WallJumpState state = states.computeIfAbsent(uuid, k -> new WallJumpState());

        // 残り回数チェック
        if (state.jumpCount >= MAX_WALL_JUMPS) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ上限に達した ({}回)", MAX_WALL_JUMPS);
            return;
        }

        Level level = player.level();
        BlockPos bp  = player.blockPosition();

        // 4方向の固体ブロックを確認して壁法線を計算
        // Minecraftの座標: X+ = East, X- = West, Z+ = South, Z- = North
        double nx = 0, nz = 0;
        if (isSolid(level, bp.east()))  nx -= 1; // 東に壁 → 西（-X）方向に弾く
        if (isSolid(level, bp.west()))  nx += 1; // 西に壁 → 東（+X）方向に弾く
        if (isSolid(level, bp.south())) nz -= 1; // 南に壁 → 北（-Z）方向に弾く
        if (isSolid(level, bp.north())) nz += 1; // 北に壁 → 南（+Z）方向に弾く

        if (nx == 0 && nz == 0) {
            // プレイヤーの1ブロック上も確認（段差に当たっている場合）
            if (isSolid(level, bp.above().east()))  nx -= 1;
            if (isSolid(level, bp.above().west()))  nx += 1;
            if (isSolid(level, bp.above().south())) nz -= 1;
            if (isSolid(level, bp.above().north())) nz += 1;
        }

        if (nx == 0 && nz == 0) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ: 壁なし");
            return; // 隣接する固体ブロックなし
        }

        // 法線ベクトルを正規化
        double len = Math.sqrt(nx * nx + nz * nz);
        nx /= len;
        nz /= len;

        // 同一壁クールダウンチェック（直前と同じ法線方向 → 使用不可）
        if (state.sameCooldown > 0
                && Math.abs(state.lastNormalX - nx) < 0.1
                && Math.abs(state.lastNormalZ - nz) < 0.1) {
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ: 同一壁クールダウン中");
            return;
        }

        // 視線の水平方向を取得
        Vec3 lookDir = player.getLookAngle();
        Vec3 look    = new Vec3(lookDir.x, 0, lookDir.z);
        double lookLen = look.length();
        if (lookLen > 0.001) look = look.scale(1.0 / lookLen); // 正規化

        // 速度計算: 壁法線方向 + 視線前方方向
        double vx = nx * WALL_NORMAL_FORCE + look.x * LOOK_FORWARD_FORCE;
        double vz = nz * WALL_NORMAL_FORCE + look.z * LOOK_FORWARD_FORCE;

        player.setDeltaMovement(vx, WALL_JUMP_Y, vz);
        player.hurtMarked = true;
        // 壁ジャンプ中はスプリント維持
        player.setSprinting(true);

        // 状態を更新
        state.jumpCount++;
        state.lastNormalX  = nx;
        state.lastNormalZ  = nz;
        state.sameCooldown = SAME_WALL_COOLDOWN;

        AggressiveMovementMod.LOGGER.debug(
            "壁ジャンプ発動 ({}/{}回) normal=({}, {})",
            state.jumpCount, MAX_WALL_JUMPS,
            String.format("%.2f", nx), String.format("%.2f", nz));
    }

    // =========================================================
    // tick処理
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // シングルプレイでの二重処理を防ぐ（サーバー側のみ）
        if (event.player.level().isClientSide()) return;

        Player player = event.player;
        UUID   uuid   = player.getUUID();
        WallJumpState state = states.get(uuid);
        if (state == null) return;

        // 着地でジャンプ回数リセット
        if (player.onGround() && state.jumpCount > 0) {
            state.jumpCount = 0;
            AggressiveMovementMod.LOGGER.debug("壁ジャンプ回数リセット（着地）");
        }

        // 同一壁クールダウンのカウントダウン
        if (state.sameCooldown > 0) {
            state.sameCooldown--;
        }

        // 状態が初期値に戻ったら削除してメモリを節約
        if (state.jumpCount == 0 && state.sameCooldown == 0) {
            states.remove(uuid);
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    /**
     * 指定したBlockPosが固体ブロックかどうかを確認
     * プレイヤーの頭部と腰部の2点を確認
     */
    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.blocksMotion()) return true;
        // 1ブロック上も確認（プレイヤーは2ブロック高さのため）
        BlockState stateAbove = level.getBlockState(pos.above());
        return !stateAbove.isAir() && stateAbove.blocksMotion();
    }

    /**
     * 指定プレイヤーの壁ジャンプ残り回数を返す（UIや外部参照用）
     */
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
}
