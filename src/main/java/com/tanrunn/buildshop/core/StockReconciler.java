package com.tanrunn.buildshop.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 持久库存与目录的调和逻辑（纯数据，可在单元测试中完整验证）。
 *
 * <p>用于数据包重载 / 服务器启动时，把"当前持久化库存"与"新目录"对齐：</p>
 * <ul>
 *   <li>目录中不存在的商品（已删除）→ 移除持久化条目；</li>
 *   <li>由有限库存改为无限库存的商品 → 移除持久化条目；</li>
 *   <li>新的有限库存商品（持久化中不存在，含明确为 0 的条目）→ 使用 JSON 初始值；</li>
 *   <li>已有的有限库存商品 → 保留持久化值，绝不重置（卖光后的 0 也保留）。</li>
 * </ul>
 */
public final class StockReconciler {

    private StockReconciler() {
    }

    /**
     * @param persisted        当前持久化库存（商品 ID → 数量，缺失 = 未初始化）
     * @param jsonInitialStock 新目录中所有有限库存商品的 JSON 初始值
     * @param catalogProductIds 新目录中全部商品 ID
     * @return 调和后的持久化库存（不会修改入参 map）
     */
    public static Map<String, Integer> reconcile(
            Map<String, Integer> persisted,
            Map<String, Integer> jsonInitialStock,
            Set<String> catalogProductIds) {
        Map<String, Integer> result = new LinkedHashMap<>(persisted);
        result.keySet().removeIf(id -> !catalogProductIds.contains(id));
        result.keySet().removeIf(id -> !jsonInitialStock.containsKey(id));
        jsonInitialStock.forEach((id, quantity) -> {
            if (!result.containsKey(id)) {
                result.put(id, Math.max(0, quantity));
            }
        });
        return result;
    }
}
