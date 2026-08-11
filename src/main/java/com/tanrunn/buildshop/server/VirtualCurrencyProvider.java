package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.api.PaymentResult;
import com.tanrunn.buildshop.api.ShopCurrencyProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * 默认虚拟货币提供者（ID: virtual_coins）。
 *
 * <p>余额持久化在 {@link ShopSavedData}，按玩家 UUID 记录。单位是整数最小单位。</p>
 */
public class VirtualCurrencyProvider implements ShopCurrencyProvider {
    public static final String ID = "virtual_coins";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "金币";
    }

    @Override
    public long balance(ServerPlayer player) {
        return ShopServer.INSTANCE.dataOf(player).virtualBalance(player.getUUID());
    }

    @Override
    public boolean canWithdraw(ServerPlayer player, long amount) {
        return balance(player) >= amount;
    }

    @Override
    public PaymentResult withdraw(ServerPlayer player, long amount, String reason, String requestId) {
        if (amount < 0) return PaymentResult.fail("buildshop.payment.negative");
        ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
        long current = data.virtualBalance(player.getUUID());
        if (current < amount) {
            return PaymentResult.fail("buildshop.payment.insufficient_balance");
        }
        data.setVirtualBalance(player.getUUID(), current - amount);
        return PaymentResult.ok();
    }

    @Override
    public PaymentResult refund(ServerPlayer player, long amount, String reason, String requestId) {
        if (amount < 0) return PaymentResult.fail("buildshop.payment.negative");
        ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
        long current = data.virtualBalance(player.getUUID());
        data.setVirtualBalance(player.getUUID(), current + amount);
        BuildShopMod.LOGGER.info("Refunded {} coins to {} (reason={}, requestId={})",
                amount, player.getGameProfile().getName(), reason, requestId);
        return PaymentResult.ok();
    }

    @Override
    public String format(long amount) {
        return String.format("%,d", amount);
    }
}
