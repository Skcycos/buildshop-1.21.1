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
        assertEquals(0, store.remaining("item")); // 耗尽后条目保留为 0，区别于未初始化(-1)
    }

    @Test
    void zeroStockIsDistinguishedFromUninitialized() {
        StockStore store = new StockStore();
        assertEquals(-1, store.remaining("x"), "未初始化必须返回 -1");
        assertFalse(store.hasFiniteStock("x"));
        store.set("x", 0);
        assertEquals(0, store.remaining("x"), "明确为 0 的库存必须返回 0");
        assertTrue(store.hasFiniteStock("x"));
    }

    @Test
    void consumeToZeroKeepsEntryForRestartPersistence() {
        StockStore store = new StockStore();
        store.set("glowstone", 2000);
        for (int i = 0; i < 2000; i++) {
            assertTrue(store.consume("glowstone", 1));
        }
        assertEquals(0, store.remaining("glowstone"));
        Map<String, Integer> snapshot = store.toMap();
        assertEquals(0, snapshot.get("glowstone"), "0 必须进入持久化快照");
        StockStore restored = StockStore.fromMap(snapshot);
        assertEquals(0, restored.remaining("glowstone"), "重启恢复后仍为 0，不被初始值补满");
    }

    @Test
    void restoreAddsBackConsumedStock() {
        StockStore store = new StockStore();
        store.set("item", 10);
        assertTrue(store.consume("item", 4));
        assertEquals(6, store.remaining("item"));
        store.restore("item", 4);
        assertEquals(10, store.remaining("item"));
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
