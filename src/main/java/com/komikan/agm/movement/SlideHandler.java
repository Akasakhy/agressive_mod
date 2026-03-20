package com.komikan.agm.movement;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スライディングハンドラ（サーバー側メインロジック）
 *
 * コンボシステムの着地検知:
 *   スライドジャンプ発動 → pendingLandingCombo に倍率を保存
 *   空中(onGround=false) → 着地(onGround=true) の遷移を検知
 *   着地した瞬間にのみコンボウィンドウ(countdown)を開始
 *   ※ ジャンプ直後はまだ onGround=true の場合があるため、
 *      必ず「一度空中を経由した着地」だけをカウント
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID)
public class SlideHandler {

    // ---- スライド定数 ----
    private static final double SLIDE_INITIAL_SPEED   = 0.55;
    private static final double SLIDE_HOLD_SPEED      = 0.38;
    private static final double SPRINT_SPEED          = 0.26;
    private static final int    MAX_SLIDE_TICKS       = 40;
    private static final int    EXIT_PHASE_TICKS      = 8;
    private static final int    SLIDE_COOLDOWN        = 10;

    /**
     * スライドジャンプY速度: 現在の水平速度に応じて線形スケール。
     *   低速（SPRINT_SPEED 以下）→ SLIDE_JUMP_BOOST_MIN
     *   高速（SLIDE_INITIAL_SPEED * COMBO_MAX_MULT 以上）→ SLIDE_JUMP_BOOST_MAX
     */
    private static final double SLIDE_JUMP_BOOST_MIN = 0.35;
    private static final double SLIDE_JUMP_BOOST_MAX = 0.55;

    // ---- コンボ定数 ----
    /** 着地後このtick以内に次のスライドでコンボ成立 */
    private static final int    COMBO_WINDOW_TICKS  = 20;
    /** 1コンボあたりの倍率加算（4段階: 1.0 → 1.1 → 1.2 → 1.3 → 1.4） */
    private static final double COMBO_INCREMENT     = 0.1;
    /** 倍率上限 */
    private static final double COMBO_MAX_MULT      = 1.4;

    // ---- 状態マップ ----
    /** スライド状態 */
    private static final Map<UUID, SlideState>  slideStates         = new ConcurrentHashMap<>();
    /** 着地待ちコンボ倍率（スライドジャンプ後、まだ空中にいる間） */
    private static final Map<UUID, Double>      pendingLandingCombo = new ConcurrentHashMap<>();
    /** アクティブなコンボ（着地済み、ウィンドウカウントダウン中） */
    private static final Map<UUID, ComboState>  comboStates         = new ConcurrentHashMap<>();
    /** 前tickにプレイヤーが空中だったか（着地の瞬間を検知するため） */
    private static final Map<UUID, Boolean>     wasAirborne         = new ConcurrentHashMap<>();

    // =========================================================
    // パブリックAPI
    // =========================================================

    /** スライド開始（キー押下パケット受信時） */
    public static void tryStartSlide(Player player) {
        UUID uuid = player.getUUID();
        SlideState state = slideStates.get(uuid);

        if (state != null && state.cooldownTicks > 0)           return;
        if (state != null && state.phase != SlidePhase.IDLE)    return;
        if (!player.isSprinting() || !player.onGround())        return;

        double comboMult = getActiveCombMult(uuid);

        Vec3 lookDir = player.getLookAngle();
        Vec3 dir     = new Vec3(lookDir.x, 0, lookDir.z).normalize();

        SlideState newState = new SlideState();
        newState.phase     = SlidePhase.SLIDING;
        newState.dirX      = dir.x;
        newState.dirZ      = dir.z;
        newState.comboMult = comboMult;
        slideStates.put(uuid, newState);

        player.setPose(Pose.SWIMMING);
        player.setSprinting(false);
        Vec3 cur = player.getDeltaMovement();
        player.setDeltaMovement(dir.x * SLIDE_INITIAL_SPEED * comboMult, cur.y,
                dir.z * SLIDE_INITIAL_SPEED * comboMult);
        player.hurtMarked = true;

        AggressiveMovementMod.LOGGER.debug("スライド開始 (コンボ{}倍): {}",
                String.format("%.1f", comboMult), player.getName().getString());
    }

    /** キー離し → 終了フェーズへ移行 */
    public static void releaseKey(Player player) {
        SlideState state = slideStates.get(player.getUUID());
        if (state == null || state.phase != SlidePhase.SLIDING) return;

        Vec3 cur = player.getDeltaMovement();
        state.speedAtExitStart = Math.sqrt(cur.x * cur.x + cur.z * cur.z);
        state.phase     = SlidePhase.EXITING;
        state.exitTicks = 0;
    }

    public static boolean isSliding(Player player) {
        SlideState state = slideStates.get(player.getUUID());
        return state != null && state.phase == SlidePhase.SLIDING;
    }

    public static void clearState(UUID uuid) {
        slideStates.remove(uuid);
        comboStates.remove(uuid);
        pendingLandingCombo.remove(uuid);
        wasAirborne.remove(uuid);
    }

    // =========================================================
    // イベントハンドラ
    // =========================================================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player  = event.player;
        UUID   uuid    = player.getUUID();
        boolean onGround = player.onGround();

        // ---- 着地検知: 空中→地面 の遷移のみを捕捉 ----
        Boolean prevAirborne = wasAirborne.get(uuid);
        boolean justLanded   = (prevAirborne != null && prevAirborne && onGround);
        wasAirborne.put(uuid, !onGround);

        if (justLanded) {
            Double pending = pendingLandingCombo.remove(uuid);
            if (pending != null) {
                ComboState combo  = new ComboState();
                combo.multiplier  = pending;
                combo.windowTicks = COMBO_WINDOW_TICKS;
                comboStates.put(uuid, combo);
                AggressiveMovementMod.LOGGER.debug(
                        "コンボウィンドウ開始 — 倍率{}倍, {}tick",
                        String.format("%.1f", pending), COMBO_WINDOW_TICKS);
            }
        }

        // ---- アクティブコンボのカウントダウン ----
        ComboState combo = comboStates.get(uuid);
        if (combo != null) {
            combo.windowTicks--;
            if (combo.windowTicks <= 0) {
                comboStates.remove(uuid);
                AggressiveMovementMod.LOGGER.debug("コンボウィンドウ切れ");
            }
        }

        // ---- スライド状態のtick ----
        SlideState state = slideStates.get(uuid);
        if (state == null) return;

        switch (state.phase) {
            case SLIDING  -> tickSliding(player, state, uuid);
            case EXITING  -> tickExiting(player, state, uuid);
            case COOLDOWN -> tickCooldown(player, state, uuid);
            default       -> {}
        }
    }

    /**
     * スライドジャンプ（SLIDING / EXITING フェーズ中にジャンプ）
     *
     * Y速度を現在の水平速度に応じてリニアにスケール:
     *   horizSpeed <= SPRINT_SPEED               → SLIDE_JUMP_BOOST_MIN (0.35)
     *   horizSpeed >= SLIDE_INITIAL_SPEED * COMBO_MAX_MULT → SLIDE_JUMP_BOOST_MAX (0.55)
     *   その間は線形補間
     */
    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        UUID       uuid  = player.getUUID();
        SlideState state = slideStates.get(uuid);
        if (state == null) return;
        if (state.phase != SlidePhase.SLIDING && state.phase != SlidePhase.EXITING) return;

        Vec3   cur        = player.getDeltaMovement();
        double horizSpeed = Math.sqrt(cur.x * cur.x + cur.z * cur.z);
        double launch     = Math.max(horizSpeed, SPRINT_SPEED);

        // 水平速度からジャンプY速度を線形補間
        // 基準範囲: [SPRINT_SPEED, SLIDE_INITIAL_SPEED * COMBO_MAX_MULT]
        double speedMin = SPRINT_SPEED;
        double speedMax = SLIDE_INITIAL_SPEED * COMBO_MAX_MULT; // 0.55 * 1.4 = 0.77
        double t        = Math.min(1.0, Math.max(0.0,
                (horizSpeed - speedMin) / (speedMax - speedMin)));
        double jumpY    = SLIDE_JUMP_BOOST_MIN + t * (SLIDE_JUMP_BOOST_MAX - SLIDE_JUMP_BOOST_MIN);

        player.setDeltaMovement(state.dirX * launch, jumpY, state.dirZ * launch);
        player.hurtMarked = true;
        player.setSprinting(true);

        state.phase         = SlidePhase.COOLDOWN;
        state.cooldownTicks = SLIDE_COOLDOWN;

        ComboState activeCombo = comboStates.get(uuid);
        double currentMult = (activeCombo != null) ? activeCombo.multiplier : 1.0;
        double nextMult    = Math.min(currentMult + COMBO_INCREMENT, COMBO_MAX_MULT);
        pendingLandingCombo.put(uuid, nextMult);

        AggressiveMovementMod.LOGGER.debug(
                "スライドジャンプ: horizSpeed={}, jumpY={}, 次コンボ待ち={}倍",
                String.format("%.3f", horizSpeed),
                String.format("%.3f", jumpY),
                String.format("%.1f", nextMult));
    }

    // =========================================================
    // フェーズ別 tick
    // =========================================================

    private static void tickSliding(Player player, SlideState state, UUID uuid) {
        state.slideTicks++;

        if (state.slideTicks >= MAX_SLIDE_TICKS) {
            Vec3 cur = player.getDeltaMovement();
            state.speedAtExitStart = Math.sqrt(cur.x * cur.x + cur.z * cur.z);
            state.phase     = SlidePhase.EXITING;
            state.exitTicks = 0;
            return;
        }

        double targetSpeed;
        if (state.slideTicks <= 5) {
            double t = state.slideTicks / 5.0;
            targetSpeed = (SLIDE_INITIAL_SPEED * (1.0 - t) + SLIDE_HOLD_SPEED * t) * state.comboMult;
        } else {
            double decay = 1.0 - ((state.slideTicks - 5.0) / MAX_SLIDE_TICKS) * 0.3;
            targetSpeed  = SLIDE_HOLD_SPEED * decay * state.comboMult;
        }

        Vec3   lookDir = player.getLookAngle();
        Vec3   dir     = new Vec3(lookDir.x, 0, lookDir.z).normalize();
        double lerp    = 0.25;
        state.dirX = state.dirX * (1.0 - lerp) + dir.x * lerp;
        state.dirZ = state.dirZ * (1.0 - lerp) + dir.z * lerp;
        double len = Math.sqrt(state.dirX * state.dirX + state.dirZ * state.dirZ);
        if (len > 0.001) { state.dirX /= len; state.dirZ /= len; }

        Vec3 cur = player.getDeltaMovement();
        player.setDeltaMovement(state.dirX * targetSpeed, cur.y, state.dirZ * targetSpeed);
        player.hurtMarked = true;
        player.setPose(Pose.SWIMMING);
    }

    private static void tickExiting(Player player, SlideState state, UUID uuid) {
        if (!player.onGround()) {
            player.setSprinting(true);
            state.phase         = SlidePhase.COOLDOWN;
            state.cooldownTicks = SLIDE_COOLDOWN;
            return;
        }

        state.exitTicks++;
        double t = (double) state.exitTicks / EXIT_PHASE_TICKS;
        double blendSpeed = state.speedAtExitStart * (1.0 - t) + SPRINT_SPEED * t;
        Vec3   cur = player.getDeltaMovement();
        player.setDeltaMovement(state.dirX * blendSpeed, cur.y, state.dirZ * blendSpeed);
        player.hurtMarked = true;
        player.setPose(Pose.SWIMMING);

        if (state.exitTicks >= EXIT_PHASE_TICKS) {
            player.setSprinting(true);
            Vec3 last = player.getDeltaMovement();
            player.setDeltaMovement(state.dirX * SPRINT_SPEED, last.y, state.dirZ * SPRINT_SPEED);
            player.hurtMarked = true;
            state.phase         = SlidePhase.COOLDOWN;
            state.cooldownTicks = SLIDE_COOLDOWN;
        }
    }

    private static void tickCooldown(Player player, SlideState state, UUID uuid) {
        state.cooldownTicks--;
        if (state.cooldownTicks <= 0) {
            slideStates.remove(uuid);
        }
    }

    // =========================================================
    // コンボヘルパー
    // =========================================================

    /** アクティブなコンボ倍率を返す（コンボがなければ 1.0） */
    private static double getActiveCombMult(UUID uuid) {
        ComboState combo = comboStates.get(uuid);
        return (combo != null) ? combo.multiplier : 1.0;
    }

    // =========================================================
    // 内部クラス
    // =========================================================

    private enum SlidePhase { IDLE, SLIDING, EXITING, COOLDOWN }

    private static class SlideState {
        SlidePhase phase           = SlidePhase.IDLE;
        int        slideTicks      = 0;
        int        exitTicks       = 0;
        int        cooldownTicks   = 0;
        double     dirX            = 0;
        double     dirZ            = 0;
        double     speedAtExitStart = 0;
        double     comboMult       = 1.0;
    }

    private static class ComboState {
        double multiplier  = 1.0;
        int    windowTicks = 0;
    }
}