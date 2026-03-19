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
 * スライドキーのデフォルトは左Shiftキー
 */
@Mod.EventBusSubscriber(modid = AggressiveMovementMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeyBindings {

    // スライドキー（デフォルト: 左Shift）
    public static final KeyMapping KEY_SLIDE = new KeyMapping(
            "key.agm.slide",                                          // 翻訳キー
            InputConstants.Type.KEYSYM,                                // 入力タイプ
            GLFW.GLFW_KEY_LEFT_SHIFT,                                  // デフォルトキー
            "key.categories.agm"                                       // カテゴリ
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY_SLIDE);
        AggressiveMovementMod.LOGGER.debug("キーバインド登録完了");
    }
}
