package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.core.ItemDispatcher;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务端背包发货适配器测试（需要真实 Minecraft 物品注册表）。
 *
 * <p>覆盖：不同物品的半满堆叠不算空间、同物品不同 components 不能合并、
 * 真实 ItemStack.getMaxStackSize()（含组件修改）、多堆叠发货与中途失败完整回滚。</p>
 */
class InventoryDispatcherTest {

    @BeforeAll
    static void boot() {
        Bootstrap.bootStrap();
    }

    private ServerPlayer playerWith(Inventory inventory) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private Inventory emptyInventory() {
        return new Inventory(mock(ServerPlayer.class));
    }

    private int comparatorTotal(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(Items.COMPARATOR)) total += stack.getCount();
        }
        return total;
    }

    @Test
    void halfFilledDifferentItemStackIsNotSpace() {
        Inventory inventory = emptyInventory();
        for (int i = 0; i < 34; i++) {
            inventory.setItem(i, new ItemStack(Items.COMPARATOR, 64));
        }
        inventory.setItem(34, new ItemStack(Items.COBBLESTONE, 32)); // 不同物品
        ServerPlayer player = playerWith(inventory);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, new ItemStack(Items.COMPARATOR));

        // 正确容量 = 1 空槽 × 64 = 64；若把圆石堆叠误算为空间则 = 96。
        assertFalse(dispatcher.canDispense(80), "半满的不同物品堆叠不能算作可用空间");
        assertTrue(dispatcher.canDispense(64), "1 个空槽应能放下 64 个");
    }

    @Test
    void sameItemDifferentComponentsCannotMerge() {
        Inventory inventory = emptyInventory();
        for (int i = 0; i < 34; i++) {
            inventory.setItem(i, new ItemStack(Items.COMPARATOR, 64));
        }
        ItemStack named = new ItemStack(Items.COMPARATOR, 32);
        named.applyComponents(DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("特殊比较器"))
                .build());
        inventory.setItem(34, named);
        ServerPlayer player = playerWith(inventory);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, new ItemStack(Items.COMPARATOR));

        assertFalse(dispatcher.canDispense(80), "同物品不同 components 的堆叠不能合并");
    }

    @Test
    void compatibleHalfFilledStackContributesItsRemainingSpace() {
        Inventory inventory = emptyInventory();
        inventory.setItem(0, new ItemStack(Items.COMPARATOR, 32)); // 兼容堆叠
        ServerPlayer player = playerWith(inventory);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, new ItemStack(Items.COMPARATOR));

        assertTrue(dispatcher.canDispense(32 + 35 * 64), "兼容半满堆叠 + 35 空槽都应计入");
        assertFalse(dispatcher.canDispense(33 + 35 * 64));
    }

    @Test
    void maxStackComesFromRealStackIncludingComponents() {
        Inventory inventory = emptyInventory();
        ServerPlayer player = playerWith(inventory);

        ItemStack template = new ItemStack(Items.COBBLESTONE);
        template.set(DataComponents.MAX_STACK_SIZE, 16);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, template);

        // 组件把最大堆叠改为 16：容量 = 36 × 16 = 576，且发货按 16 分组。
        assertTrue(dispatcher.canDispense(576));
        assertFalse(dispatcher.canDispense(577));

        assertTrue(dispatcher.dispense(17));
        int total = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(Items.COBBLESTONE)) total += stack.getCount();
        }
        assertEquals(17, total, "17 个应完整放入");
        assertEquals(16, inventory.getItem(0).getCount(), "第一组按真实最大堆叠 16");
    }

    @Test
    void multiStackDispenseSucceedsAndMerges() {
        Inventory inventory = emptyInventory();
        inventory.setItem(0, new ItemStack(Items.COMPARATOR, 10)); // 可合并
        ServerPlayer player = playerWith(inventory);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, new ItemStack(Items.COMPARATOR));

        assertTrue(dispatcher.dispense(64 + 64 + 20));
        assertEquals(10 + 64 + 64 + 20, comparatorTotal(inventory), "多堆叠发货应完整放入");
    }

    @Test
    void dispenseLeavesNoPartialItemsWhenFullInventory() {
        Inventory inventory = emptyInventory();
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        ServerPlayer player = playerWith(inventory);
        ItemDispatcher dispatcher = new ShopServer.InventoryDispatcher(player, new ItemStack(Items.COBBLESTONE));

        assertFalse(dispatcher.dispense(10), "背包已满时发货必须整体失败");
        assertEquals(36 * 64, comparatorTotalFor(inventory), "不得留下部分商品");
    }

    private int comparatorTotalFor(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(Items.COBBLESTONE)) total += stack.getCount();
        }
        return total;
    }
}
