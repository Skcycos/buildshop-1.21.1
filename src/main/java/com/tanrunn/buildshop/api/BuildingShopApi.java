package com.tanrunn.buildshop.api;

import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.core.PurchaseResult;

import net.minecraft.server.MinecraftServer;
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
     * 服务端打开玩家的建筑商店面板。
     *
     * <p>复用 {@code OpenShopPayload} 网络包，不访问任何客户端类。必须在服务端主线程调用。
     * 商店总开关关闭时沿用命令权限语义：普通玩家不能打开，具有 2 级权限的管理员仍可打开。</p>
     *
     * @param player 目标玩家
     * @return true 表示已接受打开请求；player 为 null、不在服务端主线程、网络未就绪，
     *         或商店关闭且无 2 级权限时返回 false。
     */
    public static boolean openPanel(ServerPlayer player) {
        return com.tanrunn.buildshop.server.ShopServer.INSTANCE.open(player);
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

    /**
     * 服务端只读的建筑商店摘要。
     *
     * <p>复用 {@link Config}（总开关、默认货币）、{@code ShopServer.catalog()}（启用商品数）
     * 与 {@link ShopCurrencyProvider}（默认货币的权威余额与格式化）。不复制余额计算、
     * 格式化或商品过滤逻辑；不暴露 ShopServer / ShopSavedData / 网络包 / AUI 类型。</p>
     *
     * @param player 目标玩家（必须在线）
     * @return 只读摘要
     * @throws IllegalArgumentException player 为 null
     * @throws IllegalStateException 非服务端主线程调用
     */
    public static BuildingShopSummary summary(ServerPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        MinecraftServer server = player.server;
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("must be called on the server thread");
        }
        boolean shopEnabled = Config.ENABLED.get();
        int enabledProductCount = com.tanrunn.buildshop.server.ShopServer.INSTANCE.catalog().enabledProducts().size();
        String currencyId = Config.DEFAULT_CURRENCY.get();
        if (currencyId == null) {
            currencyId = "";
        }
        ShopCurrencyProvider provider = currencyId.isBlank()
                ? null
                : com.tanrunn.buildshop.server.ShopServer.INSTANCE.currencyFor(currencyId);
        return buildSummary(shopEnabled, enabledProductCount, currencyId, provider, player);
    }

    /**
     * 纯转换（包内测试友好）：由已解析的权威数据构建摘要。
     *
     * <p>默认货币提供者缺失（未注册或货币 ID 空白）时安全降级：
     * 名称使用配置的货币 ID（可能为空串）、余额 0、格式化 "0"，不崩溃。</p>
     */
    static BuildingShopSummary buildSummary(boolean shopEnabled, int enabledProductCount,
                                            String currencyId, ShopCurrencyProvider provider,
                                            ServerPlayer player) {
        String currencyName;
        long balance;
        String formatted;
        if (provider == null) {
            currencyName = currencyId;
            balance = 0L;
            formatted = "0";
        } else {
            currencyName = provider.displayName();
            if (currencyName == null) {
                currencyName = currencyId;
            }
            balance = provider.balance(player);
            formatted = provider.format(balance);
            if (formatted == null) {
                formatted = String.valueOf(balance);
            }
        }
        return new BuildingShopSummary(shopEnabled, enabledProductCount, currencyId, currencyName, balance, formatted);
    }
}
