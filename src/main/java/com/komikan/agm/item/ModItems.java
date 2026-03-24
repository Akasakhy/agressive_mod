package com.komikan.agm.item;

import com.komikan.agm.AggressiveMovementMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * AGM アイテム登録
 *
 * DeferredRegister を使って Forge のレジストリイベントに乗せる。
 * テクスチャは assets/agm/textures/item/ に PNG を置くだけで自動適用。
 */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AggressiveMovementMod.MODID);

    // ── 二段ジャンプの羽 ────────────────────────────────────────
    public static final RegistryObject<Item> DOUBLE_JUMP_FEATHER =
            ITEMS.register("double_jump_feather",
                    () -> new DoubleJumpFeather(
                            new Item.Properties()
                                    .stacksTo(1)          // 重ねて持てない（装備感を演出）
                    )
            );
}
