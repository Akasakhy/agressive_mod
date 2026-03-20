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
 * 壁張り付きハンドラ（サーバー側）
 *
 * ── 仕様 ─────────────────────────────────────────────────────────
 * スニークキー + 空中 + 壁接触 → 張り付き開始
 *
 * [CLINGING フェーズ] 0 〜 CLING_MAX_TICKS（40tick / 2秒）
 *   毎tick Y速度を CLING_FALL_SPEED（-0.02）に固定。
 *   重力による加速を打ち消し、ほんのわずかだけ下に動く感触を演出。
 *   水平速度は 0 に設定（壁に張り付いて静止）。
 *
 * [SLIDING フェーズ] CLING_MAX_TICKS 経過後
 *   Y速度を SLIDE_FALL_SPEED（-0.08）に固定。
 *   重力のような自然な加速は起こらず、一定速でゆっくりずり落ちる。
 *
 * ── 注意 ──────────────────────────────────────────────────────────
 * player.horizontalCollision はサーバー側では正確に更新されないため、
 * 壁の有無はブロック隣接チェックで判定する。
 * クライアントの張り付き開始判定は信頼し、サーバー側では onGround のみチェック。
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class WallClingHandler {

    // ---- 定数 ----
    /** 張り付き維持時間（tick）: 40tick = 2秒 */
    private static final int    CLING_MAX_TICKS  = 40;

    /**
     * 張り付き中の Y 速度（負値 = 下方向）
     * 重力加速度（約 -0.08/tick）を上書きして、ほぼ静止させる。
     * 完全に0にしないことで「ちょっとずつ落ちている」感触を残す。
     */
    private static final double CLING_FALL_SPEED = -0.02;

    /**
     * ずり落ちフェーズの Y 速度
     * 重力の自然加速を打ち消し一定速でゆっくり落ちる。
     */
    private static final double SLIDE_FALL_SPEED = -0.08;

    // ---- 状態マップ ----
    private static final Map<UUID, ClingState> states = new ConcurrentHashMap<>();

    // =========================================================
    // パブリックAPI
    // =========================================================

    /**
     * 壁張り付き開始。
     * クライアントが「空中 + 壁接触 + スニーク押下」を確認してから送信するため、
     * サーバー側では onGround チェックのみ行う。
     * horizontalCollision はサーバーで信頼できないためチェックしない。
     */
    public static void tryStartCling(Player player) {
        if (player.onGround()) return;

        UUID uuid = player.getUUID();
        ClingState existing = states.get(uuid);
        if (existing != null && existing.phase != ClingPhase.IDLE) return;

        ClingState newState = new ClingState();
        newState.phase = ClingPhase.CLINGING;
        states.put(uuid, newState);

        // 即座にY速度を抑制
        player.setDeltaMovement(0.0, CLING_FALL_SPEED, 0.0);
        player.hurtMarked = true;

        AggressiveMovementMod.LOGGER.debug("壁張り付き開始: {}", player.getName().getString());
    }

    /** キー離し or 外部からの解除 */
    public static void releaseKey(Player player) {
        if (states.remove(player.getUUID()) != null) {
            AggressiveMovementMod.LOGGER.debug("壁張り付き解除: {}", player.getName().getString());
        }
    }

    /** 現在張り付き中かどうか */
    public static boolean isClinging(Player player) {
        ClingState state = states.get(player.getUUID());
        return state != null && state.phase != ClingPhase.IDLE;
    }

    /** ログアウト等によるリセット */
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

        Player player = event.player;
        UUID   uuid   = player.getUUID();
        ClingState state = states.get(uuid);
        if (state == null) return;

        // ── 終了判定 ──────────────────────────────────────────────
        if (player.onGround()) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("壁張り付き終了（着地）");
            return;
        }

        // horizontalCollision の代わりにブロック隣接チェック
        if (!hasAdjacentWall(player)) {
            states.remove(uuid);
            AggressiveMovementMod.LOGGER.debug("壁張り付き終了（壁から離れた）");
            return;
        }

        // ── フェーズ別の速度制御 ─────────────────────────────────
        switch (state.phase) {
            case CLINGING -> {
                state.clingTicks++;
                player.setDeltaMovement(0.0, CLING_FALL_SPEED, 0.0);
                player.hurtMarked = true;

                if (state.clingTicks >= CLING_MAX_TICKS) {
                    state.phase = ClingPhase.SLIDING;
                    AggressiveMovementMod.LOGGER.debug("壁ずり落ちフェーズ開始: {}",
                            player.getName().getString());
                }
            }
            case SLIDING -> {
                player.setDeltaMovement(0.0, SLIDE_FALL_SPEED, 0.0);
                player.hurtMarked = true;
            }
            default -> {}
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    /**
     * プレイヤーの4方向に固体ブロックが1つでもあるか確認。
     * horizontalCollision の代替として使用。
     */
    private static boolean hasAdjacentWall(Player player) {
        Level    level = player.level();
        BlockPos bp    = player.blockPosition();
        return isSolid(level, bp.east())
                || isSolid(level, bp.west())
                || isSolid(level, bp.south())
                || isSolid(level, bp.north());
    }

    /** 指定BlockPosが固体か（腰・頭の2ブロック分確認） */
    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.blocksMotion()) return true;
        BlockState above = level.getBlockState(pos.above());
        return !above.isAir() && above.blocksMotion();
    }

    // =========================================================
    // 内部クラス
    // =========================================================

    private enum ClingPhase {
        IDLE,
        CLINGING,
        SLIDING
    }

    private static class ClingState {
        ClingPhase phase      = ClingPhase.IDLE;
        int        clingTicks = 0;
    }
}