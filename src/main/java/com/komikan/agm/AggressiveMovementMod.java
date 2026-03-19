package com.komikan.agm;

import com.komikan.agm.network.ModNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Aggressive Movement Mod メインクラス
 * アクションゲームのような爽快な動きを追加するモッド
 */
@Mod(AggressiveMovementMod.MODID)
public class AggressiveMovementMod {

    public static final String MODID = "agm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AggressiveMovementMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 共通セットアップ
        modEventBus.addListener(this::commonSetup);

        // Forgeイベントバスに登録
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 共通セットアップ — ネットワークチャンネル登録
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModNetwork.register();
            LOGGER.info("Aggressive Movement Mod — ネットワーク登録完了");
        });
    }
}
