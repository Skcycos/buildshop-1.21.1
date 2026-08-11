package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.api.PaymentResult;
import com.tanrunn.buildshop.api.ShopCurrencyProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 物品货币提供者（ID: {@code items:&lt;itemId&gt;}，如 {@code items:minecraft:emerald}）。
 *
 * <p>余额 = 背包中该物品的数量；扣款 = 从背包移除；退款 = 返还物品。
 * 按单个物品作为最小单位（1 个物品 = 1 单位）。</p>
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
        int remaining = (int) amount;
        if (remaining < 0) return PaymentResult.fail("buildshop.payment.negative");
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !stack.is(item)) continue;
            int taken = Math.min(stack.getCount(), remaining);
            stack.shrink(taken);
            remaining -= taken;
        }
        if (remaining > 0) {
            return PaymentResult.fail("buildshop.payment.insufficient_balance");
        }
        player.getInventory().setChanged();
        return PaymentResult.ok();
    }

    @Override
    public PaymentResult refund(ServerPlayer player, long amount, String reason, String requestId) {
        if (item == null) return PaymentResult.fail("buildshop.payment.unknown_currency");
        int remaining = (int) amount;
        while (remaining > 0) {
            int count = Math.min(remaining, 64);
            ItemStack stack = new ItemStack(item, count);
            if (!player.getInventory().add(stack)) {
                return PaymentResult.fail("buildshop.payment.refund_failed");
            }
            remaining -= count;
        }
        player.getInventory().setChanged();
        return PaymentResult.ok();
    }

    @Override
    public String format(long amount) {
        return String.format("%,d", amount);
    }
}
