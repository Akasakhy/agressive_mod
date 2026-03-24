package com.komikan.agm.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 二段ジャンプの羽
 *
 * インベントリ（ホットバー・メインインベントリ・オフハンド）に
 * 入れているだけで二段ジャンプが有効になる「パッシブアイテム」。
 *
 * ── 判定は DoubleJumpHandler.hasFeather() で行う ─────────────
 * このクラスはアイテムの定義のみ。
 * 能力の有効/無効判定はサーバー側のハンドラが毎tick確認する。
 *
 * ── 耐久値・消耗なし ─────────────────────────────────────────
 * 永続パッシブアイテムとして設計。落としたり死亡で消えるのみ。
 */
public class DoubleJumpFeather extends Item {

    public DoubleJumpFeather(Properties properties) {
        super(properties);
    }

    /**
     * ツールチップに使い方と効果を表示する。
     */
    @Override
    public void appendHoverText(ItemStack stack,
                                @Nullable Level level,
                                List<Component> tooltipComponents,
                                TooltipFlag isAdvanced) {
        tooltipComponents.add(
                Component.translatable("item.agm.double_jump_feather.tooltip_1"));
        tooltipComponents.add(
                Component.translatable("item.agm.double_jump_feather.tooltip_2"));
    }
}
