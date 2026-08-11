package com.tanrunn.buildshop.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖设计指南测试项：24（有限库存重启/重载后不丢失）、5/6/7（库存行为）。 */
class StockStoreTest {

    @Test
    void finiteStockPersistsViaMapRoundtrip() {
        StockStore store = new StockStore();
        store.set("glowstone", 2000);
        store.set("comparator", 500);
        store.consume("glowstone", 64);

        Map<String, Integer> snapshot = store.toMap();
        StockStore restored = StockStore.fromMap(snapshot);

        assertEquals(2000 - 64, restored.remaining("glowstone"));
        assertEquals(500, restored.remaining("comparator"));
    }

    @Test
    void finiteStockNeverGoesNegative() {
        StockStore store = new StockStore();
        store.set("item", 10);
        assertFalse(store.consume("item", 11));
        assertTrue(store.consume("item", 10));  // 恰好耗尽
        assertFalse(store.consume("item", 1));  // 耗尽后无法再扣减
        assertEquals(-1, store.remaining("item")); // 耗尽后条目移除
    }

    @Test
    void retainOnlyKeepsExistingProductsAfterReload() {
        StockStore store = new StockStore();
        store.set("kept", 50);
        store.set("removed", 20);
        store.retainOnly(id -> id.equals("kept"));
        assertEquals(50, store.remaining("kept"));
        assertEquals(-1, store.remaining("removed"));
    }

    @Test
    void infiniteStockHasNoEntry() {
        StockStore store = new StockStore();
        assertEquals(-1, store.remaining("infinite_product"));
        assertFalse(store.hasFiniteStock("infinite_product"));
    }

    @Test
    void emptySnapshotRestoresEmptyStore() {
        StockStore store = StockStore.fromMap(new LinkedHashMap<>());
        assertEquals(-1, store.remaining("anything"));
    }
}
