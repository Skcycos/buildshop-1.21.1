package com.tanrunn.buildshop.api;

/**
 * 建筑商店摘要（只读、展示就绪）。
 *
 * <p>由 {@link BuildingShopApi#summary} 在服务端主线程生成；所有 String 字段
 * 均保证非 null（构造时把 null 规范化为空串）。金额与格式化由默认货币提供者
 * 的权威入口提供；默认货币提供者缺失时使用明确、稳定的降级值
 * （名称为配置的货币 ID、余额 0、格式化 "0"）。</p>
 */
public record BuildingShopSummary(
        boolean shopEnabled,
        int enabledProductCount,
        String defaultCurrencyId,
        String defaultCurrencyName,
        long defaultBalance,
        String formattedDefaultBalance) {

    public BuildingShopSummary {
        defaultCurrencyId = defaultCurrencyId == null ? "" : defaultCurrencyId;
        defaultCurrencyName = defaultCurrencyName == null ? "" : defaultCurrencyName;
        formattedDefaultBalance = formattedDefaultBalance == null ? "" : formattedDefaultBalance;
    }
}
