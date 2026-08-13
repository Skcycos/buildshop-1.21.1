package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.api.PaymentResult;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 物品货币测试（需要真实 Minecraft 物品注册表）。
 *
 * <p>覆盖：最大堆叠 64/16/1 的退款分组、扣款原子性、退款容量预检、
 * long→int 边界。保持 emerald 商品行为不变。</p>
 */
class ItemCurrencyProviderTest {

    @BeforeAll
    static void boot() {
        Bootstrap.bootStrap();
    }

    private ServerPlayer player(Inventory inventory) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), "tester"));
        return player;
    }

    private Inventory emptyInventory() {
        return new Inventory(mock(ServerPlayer.class));
    }

    private int totalIn(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.items) {
            total += stack.getCount();
        }
        return total;
    }

    // ------------------------------------------------------------ 退款分组

    @Test
    void refundEmeraldsGroupsByRealMaxStackOf64() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        ServerPlayer player = player(inventory);

        PaymentResult result = provider.refund(player, 65, "test", "req-1");

        assertTrue(result.success());
        assertEquals(65, totalIn(inventory), "65 个绿宝石应完整退还");
        assertEquals(64, inventory.getItem(0).getCount(), "第一组按最大堆叠 64");
        assertEquals(1, inventory.getItem(1).getCount(), "剩余 1 个单独一组");
    }

    @Test
    void refundSnowballsGroupsByMaxStackOf16() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:snowball");
        Inventory inventory = emptyInventory();
        ServerPlayer player = player(inventory);

        assertTrue(provider.refund(player, 17, "test", "req-1").success());

        assertEquals(17, totalIn(inventory));
        assertEquals(16, inventory.getItem(0).getCount(), "雪球最大堆叠 16，第一组 16 个");
        assertEquals(1, inventory.getItem(1).getCount());
    }

    @Test
    void refundBoatsGroupsByMaxStackOf1() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:oak_boat");
        Inventory inventory = emptyInventory();
        ServerPlayer player = player(inventory);

        assertTrue(provider.refund(player, 3, "test", "req-1").success());

        assertEquals(3, totalIn(inventory));
        assertEquals(1, inventory.getItem(0).getCount(), "船最大堆叠 1，每格 1 个");
        assertEquals(1, inventory.getItem(1).getCount());
        assertEquals(1, inventory.getItem(2).getCount());
    }

    // ------------------------------------------------------------ 扣款原子性

    @Test
    void withdrawNeverLeavesPartialChange() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        inventory.setItem(0, new ItemStack(Items.EMERALD, 5));
        ServerPlayer player = player(inventory);

        PaymentResult result = provider.withdraw(player, 10, "shop_purchase", "req-1");

        assertFalse(result.success());
        assertEquals(5, totalIn(inventory), "余额不足时不得部分扣款");
    }

    @Test
    void withdrawExactAmountSucceeds() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        inventory.setItem(0, new ItemStack(Items.EMERALD, 64));
        inventory.setItem(1, new ItemStack(Items.EMERALD, 64));
        ServerPlayer player = player(inventory);

        assertTrue(provider.withdraw(player, 100, "shop_purchase", "req-1").success());
        assertEquals(28, totalIn(inventory), "128 - 100 = 28");
    }

    // ------------------------------------------------------------ 边界与容量

    @Test
    void amountBeyondIntRangeIsRejectedWithoutChanges() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        inventory.setItem(0, new ItemStack(Items.EMERALD, 64));
        ServerPlayer player = player(inventory);

        PaymentResult result = provider.withdraw(player, Integer.MAX_VALUE + 1L, "shop_purchase", "req-1");

        assertFalse(result.success());
        assertEquals("buildshop.payment.too_large", result.messageKey());
        assertEquals(64, totalIn(inventory), "long→int 越界必须整体拒绝，不留部分变更");
    }

    @Test
    void refundRejectedWhenInventoryCannotFit() {
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, new ItemStack(Items.EMERALD, 64));
        }
        ServerPlayer player = player(inventory);
        int before = totalIn(inventory);

        PaymentResult result = provider.refund(player, 1, "shop_rollback", "req-1");

        assertFalse(result.success(), "背包放不下时退款必须整体失败");
        assertEquals("buildshop.payment.refund_failed", result.messageKey());
        assertEquals(before, totalIn(inventory), "失败不得复制或吞物品");
    }

    @Test
    void sameItemWithDifferentComponentsDoesNotCountAsRefundSpace() {
        // 回归：退款放入的是默认组件堆叠；带自定义组件的同物品堆叠不能合并，
        // 也不得被当作可用空间（否则会高估容量并吞掉玩家的特殊物品）。
        ItemCurrencyProvider provider = new ItemCurrencyProvider("minecraft:emerald");
        Inventory inventory = emptyInventory();
        for (int i = 0; i < 35; i++) {
            inventory.setItem(i, new ItemStack(Items.EMERALD, 64));
        }
        ItemStack named = new ItemStack(Items.EMERALD, 63);
        named.applyComponents(DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("特殊绿宝石"))
                .build());
        inventory.setItem(35, named);
        ServerPlayer player = player(inventory);

        // 35 格满 + 1 格带组件（不可合并）→ 没有可用空间，退款 1 个必须失败。
        PaymentResult result = provider.refund(player, 1, "shop_rollback", "req-1");

        assertFalse(result.success(), "带自定义组件的堆叠不能作为退款合并空间");
        assertTrue(ItemStack.isSameItemSameComponents(named, inventory.getItem(35)), "玩家的特殊绿宝石必须原样保留");
    }
}
