package com.tanrunn.buildshop.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有限库存（纯数据）。
 *
 * <p>只保存有限库存商品的数量；无限库存商品不占用条目。
 * <p>零库存语义：条目 {@code 0} 表示"已初始化的空库存"，与"尚未初始化"
 * （条目缺失，{@link #remaining} 返回 -1）严格区分。卖光后条目保留为 0，
 * 重启或重载不会回填初始值。
 * 提供 {@link #toMap()} / {@link #fromMap(Map)} 用于 SavedData 持久化。</p>
 */
public final class StockStore {

    private final Map<String, Integer> remaining = new LinkedHashMap<>();

    /** 读取剩余库存；无限库存或未知商品返回 -1。 */
    public int remaining(String productId) {
        return remaining.getOrDefault(productId, -1);
    }

    public boolean hasFiniteStock(String productId) {
        return remaining.containsKey(productId);
    }

    /** 尝试扣减库存。库存不足或未初始化时返回 false 且不扣减。 */
    public boolean consume(String productId, int quantity) {
        Integer current = remaining.get(productId);
        if (current == null) return false;
        if (quantity > current) return false;
        remaining.put(productId, current - quantity);
        return true;
    }

    /** 把扣减的库存加回（发货失败回滚）。 */
    public void restore(String productId, int quantity) {
        int next = Math.max(0, remaining.getOrDefault(productId, 0)) + quantity;
        remaining.put(productId, next);
    }

    /** 初始化或重置有限库存数量。负值视为未初始化（移除条目）。 */
    public void set(String productId, int quantity) {
        if (quantity < 0) {
            remaining.remove(productId);
        } else {
            remaining.put(productId, quantity);
        }
    }

    /** 清空全部库存条目。 */
    public void clear() {
        remaining.clear();
    }

    /** 只保留当前目录中仍存在的商品库存（数据包重载后调用）。 */
    public void retainOnly(java.util.function.Predicate<String> exists) {
        remaining.keySet().removeIf(id -> !exists.test(id));
    }

    public Map<String, Integer> toMap() {
        return Map.copyOf(remaining);
    }

    public static StockStore fromMap(Map<String, Integer> snapshot) {
        StockStore store = new StockStore();
        if (snapshot != null) {
            snapshot.forEach(store::set);
        }
        return store;
    }

    @Override
    public String toString() {
        return "StockStore" + remaining;
    }
}
