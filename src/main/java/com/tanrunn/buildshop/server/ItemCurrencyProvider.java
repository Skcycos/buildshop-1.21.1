package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.api.PaymentResult;
import com.tanrunn.buildshop.api.ShopCurrencyProvider;
import com.tanrunn.buildshop.core.FitCalculator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品货币提供者（ID: {@code items:&lt;itemId&gt;}，如 {@code items:minecraft:emerald}）。
 *
 * <p>余额 = 背包中该物品的数量；扣款 = 从背包移除；退款 = 返还物品。
 * 按单个物品作为最小单位（1 个物品 = 1 单位）。</p>
 *
 * <p>原子性：扣款前先校验总额，失败不留部分变更；退款前先校验背包容量，
 * 放不下则整体失败并回滚已放入部分（不复制、不吞物品）。退款分组使用物品真实
 * 最大堆叠数（兼容最大堆叠 64/16/1 的货币物品）。</p>
 */
public class ItemCurrencyProvider implements ShopCurrencyProvider {

    private final String id;
    private final String displayName;
    private final Item item;

    public ItemCurrencyProvider(String itemId) {
        this.id = "items:" + itemId;
        this.item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        this.displayName = item != null ? item.getName(ItemStack.EMPTY).getString() : itemId;
    }

    public static boolean isItemCurrencyId(String id) {
        return id != null && id.startsWith("items:");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public long balance(ServerPlayer player) {
        if (item == null) return 0;
        return player.getInventory().items.stream()
                .filter(stack -> !stack.isEmpty() && stack.is(item))
                .mapToLong(ItemStack::getCount)
                .sum();
    }

    @Override
    public boolean canWithdraw(ServerPlayer player, long amount) {
        return balance(player) >= amount;
    }

    @Override
    public PaymentResult withdraw(ServerPlayer player, long amount, String reason, String requestId) {
        if (item == null) return PaymentResult.fail("buildshop.payment.unknown_currency");
        if (amount < 0) return PaymentResult.fail("buildshop.payment.negative");
        if (amount > Integer.MAX_VALUE) return PaymentResult.fail("buildshop.payment.too_large");
        // 先校验总额，确认足够才动手，失败不会留下部分扣款。
        if (balance(player) < amount) {
            return PaymentResult.fail("buildshop.payment.insufficient_balance");
        }
        int remaining = (int) amount;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !stack.is(item)) continue;
            int taken = Math.min(stack.getCount(), remaining);
            stack.shrink(taken);
            remaining -= taken;
        }
        // 防御：主线程内预校验过，理论上不会出现部分扣款；出现则记录错误。
        if (remaining > 0) {
            BuildShopMod.LOGGER.error("[Shop] item currency withdraw left partial change: player={} amount={} missing={}",
                    player.getGameProfile().getName(), amount, remaining);
            return PaymentResult.fail("buildshop.payment.withdraw_partial");
        }
        inventory.setChanged();
        return PaymentResult.ok();
    }

    @Override
    public PaymentResult refund(ServerPlayer player, long amount, String reason, String requestId) {
        if (item == null) return PaymentResult.fail("buildshop.payment.unknown_currency");
        if (amount < 0) return PaymentResult.fail("buildshop.payment.negative");
        if (amount > Integer.MAX_VALUE) return PaymentResult.fail("buildshop.payment.too_large");
        int remaining = (int) amount;
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        // 退款放入的是"默认组件"的货币堆叠：只有物品和 Data Components 都一致的
        // 已有堆叠才能合并（带自定义组件的同物品堆叠不算可用空间）。
        ItemStack currencyTemplate = new ItemStack(item);

        Inventory inventory = player.getInventory();
        int freeSlots = 0;
        List<FitCalculator.Slot> slots = new ArrayList<>(40);
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) {
                freeSlots++;
            } else if (ItemStack.isSameItemSameComponents(currencyTemplate, stack)) {
                slots.add(new FitCalculator.Slot(stack.getCount(), stack.getMaxStackSize(), true));
            }
        }
        // 预校验背包容量：放不下则整体失败，绝不复制或吞物品。
        if (!FitCalculator.fits(slots, maxStack, freeSlots, remaining)) {
            BuildShopMod.LOGGER.error("[Shop] item currency refund failed: player={} amount={} reason={} requestId={} (inventory cannot fit)",
                    player.getGameProfile().getName(), amount, reason, requestId);
            return PaymentResult.fail("buildshop.payment.refund_failed");
        }

        // 快照 36 个主槽位：预检通过后 add 理论上不会失败；万一失败则精确恢复
        // 退款前的背包状态（绝不按类型删除玩家其他物品）。
        ItemStack[] snapshot = new ItemStack[inventory.items.size()];
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            snapshot[i] = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }

        int placed = 0;
        while (remaining > 0) {
            int chunk = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item, chunk);
            if (!inventory.add(stack)) {
                restoreSnapshot(inventory, snapshot);
                BuildShopMod.LOGGER.error("[Shop] item currency refund failed mid-way: player={} amount={} reason={} requestId={}",
                        player.getGameProfile().getName(), amount, reason, requestId);
                return PaymentResult.fail("buildshop.payment.refund_failed");
            }
            placed += chunk;
            remaining -= chunk;
        }
        inventory.setChanged();
        return PaymentResult.ok();
    }

    /** 把 36 个主槽位精确恢复到快照状态（回滚退款，不触碰其他物品）。 */
    private static void restoreSnapshot(Inventory inventory, ItemStack[] snapshot) {
        for (int i = 0; i < inventory.items.size() && i < snapshot.length; i++) {
            inventory.setItem(i, snapshot[i].copy());
        }
        inventory.setChanged();
    }

    @Override
    public String format(long amount) {
        return String.format("%,d", amount);
    }
}
