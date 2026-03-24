package com.komikan.agm.item;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * AGM クリエイティブタブ登録
 *
 * 1.20.1 では CreativeModeTab も DeferredRegister で登録する。
 * アイコンは「二段ジャンプの羽」を使用。
 * 将来アイテムが増えた場合は withTabContents() 内に追記するだけでよい。
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AggressiveMovementMod.MODID);

    public static final RegistryObject<CreativeModeTab> AGM_TAB =
            CREATIVE_MODE_TABS.register("agm_tab", () ->
                    CreativeModeTab.builder()
                            // タブのアイコン
                            .icon(() -> new ItemStack(ModItems.DOUBLE_JUMP_FEATHER.get()))
                            // タブの表示名（翻訳キー）
                            .title(Component.translatable("itemGroup.agm.agm_tab"))
                            // タブに表示するアイテム
                            .displayItems((parameters, output) -> {
                                output.accept(ModItems.DOUBLE_JUMP_FEATHER.get());
                                // 将来アイテムを追加する場合はここに output.accept(...) を追記
                            })
                            .build()
            );
}
