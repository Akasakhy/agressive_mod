package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.network.ModNetwork;
import com.komikan.agm.network.SlideC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * クライアント側スライディングハンドラ
 * キー押下 → スライド開始パケット送信
 * キー離し → スライド終了パケット送信
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class SlideClientHandler {

    // スライド中フラグ（クライアント側で追跡）
    private static boolean isSliding = false;
    private static boolean wasKeyDown = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            wasKeyDown = false;
            isSliding = false;
            return;
        }

        boolean isKeyDown = ModKeyBindings.KEY_SLIDE.isDown();

        if (!wasKeyDown && isKeyDown) {
            // キーを押した瞬間 → スライド開始（スプリント中 + 地面のみ）
            if (player.isSprinting() && player.onGround()) {
                ModNetwork.CHANNEL.sendToServer(new SlideC2SPacket(false));
                isSliding = true;
                AggressiveMovementMod.LOGGER.debug("スライド開始パケット送信");
            }
        } else if (wasKeyDown && !isKeyDown && isSliding) {
            // キーを離した瞬間 → スライド終了リクエスト
            ModNetwork.CHANNEL.sendToServer(new SlideC2SPacket(true));
            isSliding = false;
            AggressiveMovementMod.LOGGER.debug("スライド終了パケット送信");
        }

        wasKeyDown = isKeyDown;
    }

    /** 外部からスライド状態をリセット（例: ログアウト時） */
    public static void resetState() {
        isSliding = false;
        wasKeyDown = false;
    }
}
