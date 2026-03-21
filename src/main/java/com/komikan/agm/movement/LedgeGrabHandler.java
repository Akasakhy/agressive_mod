package com.komikan.agm.movement;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * レッジグラブハンドラ（サーバー側メインロジック）
 *
 * ── CLIMB_Y 動的計算 ──────────────────────────────────────────────
 * 旧: CLIMB_Y = 0.50 固定
 *   → 高さ3ブロック（必要上昇量 ≈ 2ブロック超）に届かなかった
 *
 * 新: GrabState に blockTopY を保存し、climb() で逆算
 *   needed = blockTopY + 0.5 - player.getY()
 *   climbY = clamp(0.42 + needed * 0.13,  min=0.45, max=0.95)
 *
 *   高さ別の目安:
 *     1ブロック (needed≈1.0) → 0.55
 *     2ブロック (needed≈2.0) → 0.68
 *     3ブロック (needed≈3.0) → 0.81
 *
 * ── findLedge を public に変更 ────────────────────────────────────
 * クライアント側ハンドラが同一ロジックで縁を事前確認するために公開。
 * これにより horizontalCollision なしで発動タイミングを検知できる。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class LedgeGrabHandler {

    // ---- 定数 ----
    private static final int    MAX_HANG_TICKS  = 60;
    private static final int    MAX_CLIMB_TICKS = 25;
    private static final double CLIMB_FORWARD   = 0.28;

    public static final double LEDGE_DIFF_MAX  =  0.5;
    public static final double LEDGE_DIFF_MIN  = -0.8;

    // ---- 状態マップ ----
    private static final Map<UUID, GrabState> states = new ConcurrentHashMap<>();

    // =========================================================
    // パブリックAPI
    // =========================================================

    public static void tryStartGrab(Player player) {
        if (player.onGround()) return;

        UUID      uuid     = player.getUUID();
        GrabState existing = states.get(uuid);
        if (existing != null && existing.phase != GrabPhase.IDLE) return;

        LedgeInfo ledge = findLedge(player);
        if (ledge == null) {
            AggressiveMovementMod.LOGGER.debug("レッジグラブ: 縁が見つからない");
            return;
        }

        GrabState newState = new GrabState();
        newState.phase     = GrabPhase.HANGING;
        newState.wallDirX  = ledge.dirX;
        newState.wallDirZ  = ledge.dirZ;
        newState.blockTopY = ledge.blockTopY;
        states.put(uuid, newState);

        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.hurtMarked = true;

        AggressiveMovementMod.LOGGER.debug(
                "レッジグラブ開始 dir=({}, {}) blockTopY={}: {}",
                String.format("%.2f", ledge.dirX),
                String.format("%.2f", ledge.dirZ),
                String.format("%.2f", ledge.blockTopY),
                player.getName().getString());
    }

    public static void climb(Player player) {
        UUID      uuid  = player.getUUID();
        GrabState state = states.get(uuid);
        if (state == null || state.phase != GrabPhase.HANGING) return;

        state.phase      = GrabPhase.CLIMBING;
        state.climbTicks = 0;

        // ── CLIMB_Y 動的計算 ──────────────────────────────────
        // ぶら下がり中の足位置（player.getY()）からブロック上面（state.blockTopY）に
        // 足が乗るまでに必要な上昇量を逆算してジャンプ初速を決定する。
        double needed = state.blockTopY + 0.5 - player.getY();
        double climbY = Math.max(0.45, Math.min(0.95, 0.42 + needed * 0.13));

        player.setDeltaMovement(
                state.wallDirX * CLIMB_FORWARD,
                climbY,
                state.wallDirZ * CLIMB_FORWARD);
        player.hurtMarked = true;
        player.setSprinting(false);

        AggressiveMovementMod.LOGGER.debug(
                "レッジグラブ登り開始 needed={} climbY={}: {}",
                String.format("%.2f", needed),
                String.format("%.2f", climbY),
                player.getName().getString());
    }

    public static void release(Player player) {
        if (states.remove(player.getUUID()) != null) {
            AggressiveMovementMod.LOGGER.debug("レッジグラブ解除: {}", player.getName().getString());
        }
    }

    public static boolean isHanging(Player player) {
        GrabState state = states.get(player.getUUID());
        return state != null && state.phase == GrabPhase.HANGING;
    }

    public static void clearState(UUID uuid) {
        states.remove(uuid);
    }

    // =========================================================
    // tick処理
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player    player = event.player;
        UUID      uuid   = player.getUUID();
        GrabState state  = states.get(uuid);
        if (state == null) return;

        switch (state.phase) {
            case HANGING  -> tickHanging(player, state, uuid);
            case CLIMBING -> tickClimbing(player, state, uuid);
            default       -> {}
        }
    }

    private static void tickHanging(Player player, GrabState state, UUID uuid) {
        if (player.onGround()) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("レッジグラブ終了（着地）");
            return;
        }
        if (!hasAdjacentWall(player)) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("レッジグラブ終了（壁なし）");
            return;
        }
        state.hangTicks++;
        if (state.hangTicks >= MAX_HANG_TICKS) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("レッジグラブ終了（タイムアウト）");
            return;
        }
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.hurtMarked = true;
    }

    private static void tickClimbing(Player player, GrabState state, UUID uuid) {
        state.climbTicks++;
        if (player.onGround() || state.climbTicks >= MAX_CLIMB_TICKS) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("レッジグラブ登り完了 ({}tick)", state.climbTicks);
        }
    }

    // =========================================================
    // 縁検知（public: クライアント側でも呼び出し可能）
    // =========================================================

    /**
     * 4方向 × dy=0〜3 をスキャンし掴める縁を返す。
     * public にしてクライアント側ハンドラが事前確認できるようにする。
     */
    public static LedgeInfo findLedge(Player player) {
        Level    level       = player.level();
        BlockPos bp          = player.blockPosition();
        double   playerHeadY = player.getY() + player.getBbHeight();

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : dirs) {
            for (int dy = 3; dy >= 0; dy--) {
                BlockPos checkPos = bp.offset(dir[0], dy, dir[1]);
                if (!isSolid(level, checkPos)) continue;

                double blockTopY = checkPos.getY() + 1.0;
                double diff      = blockTopY - playerHeadY;
                if (diff < LEDGE_DIFF_MIN || diff > LEDGE_DIFF_MAX) continue;

                BlockPos above = checkPos.above();
                if (!level.getBlockState(above).isAir()
                        || !level.getBlockState(above.above()).isAir()) continue;

                return new LedgeInfo(dir[0], dir[1], blockTopY);
            }
        }
        return null;
    }

    private static boolean hasAdjacentWall(Player player) {
        Level    level = player.level();
        BlockPos bp    = player.blockPosition();
        return isSolid(level, bp.east())              || isSolid(level, bp.west())
                || isSolid(level, bp.south())         || isSolid(level, bp.north())
                || isSolid(level, bp.above().east())  || isSolid(level, bp.above().west())
                || isSolid(level, bp.above().south()) || isSolid(level, bp.above().north());
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.blocksMotion();
    }

    // =========================================================
    // 内部クラス
    // =========================================================

    private enum GrabPhase { IDLE, HANGING, CLIMBING }

    private static class GrabState {
        GrabPhase phase      = GrabPhase.IDLE;
        int       hangTicks  = 0;
        int       climbTicks = 0;
        double    wallDirX   = 0;
        double    wallDirZ   = 0;
        double    blockTopY  = 0;
    }

    /** 縁情報（サーバー・クライアント共用） */
    public static class LedgeInfo {
        public final double dirX;
        public final double dirZ;
        public final double blockTopY;

        public LedgeInfo(double dirX, double dirZ, double blockTopY) {
            this.dirX      = dirX;
            this.dirZ      = dirZ;
            this.blockTopY = blockTopY;
        }
    }
}
