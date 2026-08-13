package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.core.Category;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店客户端数据模型（纯 Java，可在单元测试中验证）。
 *
 * <p>客户端渲染全部从该模型读取；购买结果先更新模型，再重绘卡片，
 * 后续搜索、排序、分类切换不会回退到旧库存/旧余额。服务端仍是最终权威。</p>
 */
public final class ShopClientModel {

    private final List<CategoryDto> categories = new ArrayList<>();
    private final List<ProductDto> products = new ArrayList<>();
    private final Map<String, String> balances = new LinkedHashMap<>();
    private final Map<String, Long> balanceAmounts = new LinkedHashMap<>();
    private final Map<String, String> currencyNames = new LinkedHashMap<>();
    private String defaultCurrency = Category.ALL_ID;
    private boolean hideEmptyCategories = true;

    public void applySync(SyncShopPayload payload) {
        if (payload == null) return;
        categories.clear();
        categories.addAll(payload.categories());
        products.clear();
        products.addAll(payload.products());
        balances.clear();
        balances.putAll(payload.balances());
        balanceAmounts.clear();
        balanceAmounts.putAll(payload.balanceAmounts());
        currencyNames.clear();
        currencyNames.putAll(payload.currencyNames());
        defaultCurrency = payload.defaultCurrency();
        hideEmptyCategories = payload.hideEmptyCategories();
    }

    /** 购买结果：合并最新余额（文本 + 数值），避免余额不足提示过期。 */
    public void applyBalanceUpdates(Map<String, String> formatted, Map<String, Long> amounts) {
        if (formatted != null) {
            balances.putAll(formatted);
        }
        if (amounts != null) {
            balanceAmounts.putAll(amounts);
        }
    }

    /** 购买结果：更新商品库存到数据模型（搜索/排序/重绘后仍然正确）。 */
    public void applyStockUpdate(String productId, int remaining) {
        for (int i = 0; i < products.size(); i++) {
            ProductDto product = products.get(i);
            if (product.id().equals(productId) && product.stockRemaining() != remaining) {
                products.set(i, product.withStockRemaining(remaining));
                return;
            }
        }
    }

    public List<CategoryDto> categories() {
        return categories;
    }

    public List<ProductDto> products() {
        return products;
    }

    public ProductDto product(String id) {
        for (ProductDto product : products) {
            if (product.id().equals(id)) return product;
        }
        return null;
    }

    public String balance(String currency) {
        return balances.getOrDefault(currency, "—");
    }

    public long balanceAmount(String currency) {
        return balanceAmounts.getOrDefault(currency, 0L);
    }

    public String currencyName(String currency) {
        return currencyNames.getOrDefault(currency, currency);
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    public boolean hideEmptyCategories() {
        return hideEmptyCategories;
    }
}
