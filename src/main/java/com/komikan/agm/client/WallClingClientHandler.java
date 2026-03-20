package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.komikan.agm.network.ModNetwork;
import com.komikan.agm.network.WallClingC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 壁張り付きクライアントハンドラ
 *
 * 判定ロジック:
 *   KEY_WALL_CLING + 空中 + horizontalCollision → 張り付き開始パケット
 *   キー離し or 着地 → 解除パケット
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, value = Dist.CLIENT)
public class WallClingClientHandler {

    private static boolean isClinging = false;
    private static boolean wasKeyDown = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.screen != null) {
            if (isClinging) {
                ModNetwork.CHANNEL.sendToServer(new WallClingC2SPacket(true));
                isClinging = false;
            }
            wasKeyDown = false;
            return;
        }

        boolean isKeyDown    = ModKeyBindings.KEY_WALL_CLING.isDown();
        boolean inAir        = !player.onGround();
        boolean touchingWall = player.horizontalCollision;

        // キーを押している + 空中 + 壁接触 → 張り付き開始
        if (!isClinging && isKeyDown && inAir && touchingWall) {
            ModNetwork.CHANNEL.sendToServer(new WallClingC2SPacket(false));
            isClinging = true;
            AggressiveMovementMod.LOGGER.debug("壁張り付き開始パケット送信");
        }

        // 解除条件: キー離し or 着地 or 壁から離れた
        if (isClinging) {
            boolean shouldRelease = !isKeyDown
                    || player.onGround()
                    || !touchingWall;

            if (shouldRelease) {
                ModNetwork.CHANNEL.sendToServer(new WallClingC2SPacket(true));
                isClinging = false;
                AggressiveMovementMod.LOGGER.debug("壁張り付き解除パケット送信");
            }
        }

        wasKeyDown = isKeyDown;
    }

    public static void resetState() {
        isClinging = false;
        wasKeyDown = false;
    }
}
