package com.tanrunn.buildshop.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 库存调和逻辑测试：首次初始化、重载不重置、零库存不补满、删除清理。
 */
class StockReconcilerTest {

    private static Map<String, Integer> persisted(String... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], Integer.parseInt(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void firstStartInitializesAllFiniteProducts() {
        Map<String, Integer> jsonInitial = new LinkedHashMap<>();
        jsonInitial.put("comparator", 500);
        jsonInitial.put("glowstone", 2000);
        jsonInitial.put("sea_lantern", 800);

        Map<String, Integer> result = StockReconciler.reconcile(
                new LinkedHashMap<>(), jsonInitial,
                Set.of("comparator", "glowstone", "sea_lantern", "cobblestone"));

        assertEquals(500, result.get("comparator"));
        assertEquals(2000, result.get("glowstone"));
        assertEquals(800, result.get("sea_lantern"));
        assertFalse(result.containsKey("cobblestone"), "无限库存商品不应有库存条目");
    }

    @Test
    void reloadKeepsExistingStock() {
        Map<String, Integer> jsonInitial = Map.of("comparator", 500, "glowstone", 2000);
        Map<String, Integer> persisted = persisted("comparator", "123", "glowstone", "2000");

        Map<String, Integer> result = StockReconciler.reconcile(
                persisted, jsonInitial, Set.of("comparator", "glowstone"));

        assertEquals(123, result.get("comparator"), "已初始化的库存不得被 JSON 初始值重置");
        assertEquals(2000, result.get("glowstone"));
    }

    @Test
    void soldOutZeroSurvivesReload() {
        Map<String, Integer> jsonInitial = Map.of("comparator", 500);
        Map<String, Integer> persisted = persisted("comparator", "0");

        Map<String, Integer> result = StockReconciler.reconcile(
                persisted, jsonInitial, Set.of("comparator"));

        assertEquals(0, result.get("comparator"), "卖完的 0 库存重载后必须保持 0，不能补满");
    }

    @Test
    void newFiniteProductUsesJsonInitialValue() {
        Map<String, Integer> jsonInitial = new LinkedHashMap<>();
        jsonInitial.put("comparator", 500);
        jsonInitial.put("glowstone", 2000);
        Map<String, Integer> persisted = persisted("comparator", "100");

        Map<String, Integer> result = StockReconciler.reconcile(
                persisted, jsonInitial, Set.of("comparator", "glowstone"));

        assertEquals(100, result.get("comparator"));
        assertEquals(2000, result.get("glowstone"), "新增有限库存商品才使用 JSON 初始值");
    }

    @Test
    void deletedProductStockIsCleaned() {
        Map<String, Integer> jsonInitial = Map.of("comparator", 500);
        Map<String, Integer> persisted = persisted("comparator", "100", "removed", "42");

        Map<String, Integer> result = StockReconciler.reconcile(
                persisted, jsonInitial, Set.of("comparator"));

        assertFalse(result.containsKey("removed"), "已删除商品的持久化库存应被清理");
        assertEquals(100, result.get("comparator"));
    }

    @Test
    void productThatBecameInfiniteLosesStockEntry() {
        Map<String, Integer> jsonInitial = Map.of();
        Map<String, Integer> persisted = persisted("comparator", "100");

        Map<String, Integer> result = StockReconciler.reconcile(
                persisted, jsonInitial, Set.of("comparator"));

        assertFalse(result.containsKey("comparator"), "由有限改为无限的商品不应保留库存条目");
    }

    @Test
    void reconcileNeverMutatesInput() {
        Map<String, Integer> jsonInitial = Map.of("comparator", 500);
        Map<String, Integer> persisted = persisted("comparator", "100", "removed", "42");

        StockReconciler.reconcile(persisted, jsonInitial, Set.of("comparator"));

        assertEquals(100, persisted.get("comparator"), "入参持久化 map 不得被修改");
        assertEquals(42, persisted.get("removed"));
    }
}
