package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.core.StockMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端数据模型测试：购买结果必须同时更新数值余额与库存模型，
 * 后续搜索/排序/重绘（重新读取模型）不会回退到旧值。
 */
class ShopClientModelTest {

    private static ProductDto comparator(int stockRemaining) {
        return new ProductDto("comparator", "minecraft:comparator", null, "红石比较器", "",
                "virtual_coins", 12, "12 金币", 16, 64, StockMode.FINITE, stockRemaining,
                true, List.of("redstone"), 20);
    }

    private static SyncShopPayload syncPayload(int stockRemaining, long balance) {
        return new SyncShopPayload(
                List.of(),
                List.of(comparator(stockRemaining)),
                Map.of("virtual_coins", "1,000"),
                Map.of("virtual_coins", balance),
                Map.of("virtual_coins", "金币"),
                "virtual_coins",
                true
        );
    }

    @Test
    void purchaseResultUpdatesBothFormattedAndNumericBalance() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(500, 10_000));

        model.applyBalanceUpdates(Map.of("virtual_coins", "9,988"), Map.of("virtual_coins", 9_988L));

        assertEquals("9,988", model.balance("virtual_coins"), "格式化余额必须更新");
        assertEquals(9_988L, model.balanceAmount("virtual_coins"), "数值余额必须更新（余额不足警告依赖它）");
    }

    @Test
    void purchaseResultStockUpdateSurvivesReRenders() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(500, 10_000));

        model.applyStockUpdate("comparator", 499);

        // 后续搜索/排序/重绘都重新从模型读取：库存必须保持扣减后的值。
        ProductDto product = model.product("comparator");
        assertNotNull(product);
        assertEquals(499, product.stockRemaining(), "模型中的库存必须更新");
        assertEquals(499, model.products().get(0).stockRemaining(), "渲染列表读取的必须是新库存");
    }

    @Test
    void stockUpdateToZeroIsKept() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(1, 10_000));

        model.applyStockUpdate("comparator", 0);

        assertEquals(0, model.product("comparator").stockRemaining(), "卖完后的 0 必须保留在模型中");
    }

    @Test
    void stockUpdateForUnknownProductIsIgnored() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(500, 10_000));

        model.applyStockUpdate("unknown", 42);

        assertNull(model.product("unknown"));
        assertEquals(500, model.product("comparator").stockRemaining());
    }

    @Test
    void applyPurchaseResultKeepsEverythingConsistent() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(500, 10_000));

        PurchaseResultPayload payload = new PurchaseResultPayload(
                "req-1", true, "buildshop.result.success", 1, 12,
                Map.of("virtual_coins", "9,988"),
                Map.of("virtual_coins", 9_988L),
                Map.of("comparator", 499)
        );
        model.applyBalanceUpdates(payload.balances(), payload.balanceAmounts());
        payload.stockUpdates().forEach(model::applyStockUpdate);

        assertEquals(499, model.products().get(0).stockRemaining());
        assertEquals(9_988L, model.balanceAmount("virtual_coins"));
        assertTrue(model.balance("virtual_coins").startsWith("9,988"));
    }

    @Test
    void freshSyncReplacesEntireModel() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(syncPayload(500, 10_000));
        model.applyStockUpdate("comparator", 100);

        model.applySync(syncPayload(800, 20_000));

        assertEquals(800, model.product("comparator").stockRemaining(), "新同步必须覆盖旧模型");
        assertEquals(20_000L, model.balanceAmount("virtual_coins"));
    }
}
