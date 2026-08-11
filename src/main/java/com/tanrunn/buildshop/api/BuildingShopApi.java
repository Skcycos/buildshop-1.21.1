package com.tanrunn.buildshop.api;

import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.core.PurchaseResult;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * 建筑商店公开 API。
 *
 * <p>外部 Mod 可注册货币提供者、读取商品目录、发起购买。所有方法必须在服务端主线程调用。</p>
 */
public final class BuildingShopApi {
    private BuildingShopApi() {
    }

    /** 注册货币提供者（外部 Mod 初始化时调用；重复 ID 覆盖）。 */
    public static void registerCurrencyProvider(ShopCurrencyProvider provider) {
        com.tanrunn.buildshop.server.CurrencyRegistry.INSTANCE.register(provider);
    }

    public static Optional<ShopCurrencyProvider> currencyProvider(String id) {
        return com.tanrunn.buildshop.server.CurrencyRegistry.INSTANCE.get(id);
    }

    public static List<Product> products() {
        return com.tanrunn.buildshop.server.ShopServer.INSTANCE.catalog().products();
    }

    public static Optional<Product> product(String id) {
        return com.tanrunn.buildshop.server.ShopServer.INSTANCE.catalog().product(id);
    }

    /**
     * 服务端权威购买入口。
     *
     * @return 购买结果；成功时已扣款、已发货、已扣库存。
     */
    public static PurchaseResult purchase(ServerPlayer player, String productId, PurchaseMode mode,
                                          int requestedQuantity, String requestId) {
        return com.tanrunn.buildshop.server.ShopServer.INSTANCE.purchase(player, productId, mode, requestedQuantity, requestId);
    }
}
