package com.tanrunn.buildshop.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 覆盖设计指南测试项：23（数据包重载后商品和分类正确更新）。 */
class CatalogStoreTest {

    @Test
    void reloadSwapsCatalogAtomically() {
        CatalogStore store = new CatalogStore();
        assertNull(store.catalog().product("a").orElse(null));

        ProductCatalog first = ProductCatalog.fromJson(Map.of(), Map.of(
                "a", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":1}")
        ));
        store.set(first);
        assertEquals("minecraft:stone", store.catalog().product("a").orElseThrow().itemId());

        ProductCatalog second = ProductCatalog.fromJson(Map.of(), Map.of(
                "b", JsonParser.parseString("{\"item\":\"minecraft:glass\",\"unitPrice\":2}")
        ));
        store.set(second);
        assertNull(store.catalog().product("a").orElse(null));
        assertEquals("minecraft:glass", store.catalog().product("b").orElseThrow().itemId());
        assertEquals(1, store.catalog().productCount());
    }

    @Test
    void clearEmptiesCatalog() {
        CatalogStore store = new CatalogStore();
        store.set(ProductCatalog.fromJson(Map.of(), Map.of(
                "a", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":1}")
        )));
        store.clear();
        assertEquals(0, store.catalog().productCount());
    }
}
