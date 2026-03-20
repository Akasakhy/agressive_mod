package com.komikan.agm.client;

import com.komikan.agm.AggressiveMovementMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * キーバインド登録
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeyBindings {

    // スライドキー（デフォルト: 左Shift）
    public static final KeyMapping KEY_SLIDE = new KeyMapping(
            "key.agm.slide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_SHIFT,
            "key.categories.agm"
    );

    // 壁張り付きキー（デフォルト: Z）
    public static final KeyMapping KEY_WALL_CLING = new KeyMapping(
            "key.agm.wall_cling",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "key.categories.agm"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY_SLIDE);
        event.register(KEY_WALL_CLING);
        AggressiveMovementMod.LOGGER.debug("キーバインド登録完了");
    }
}
