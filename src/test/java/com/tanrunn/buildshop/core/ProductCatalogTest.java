package com.tanrunn.buildshop.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖设计指南测试项：2（商品 JSON 加载）、3（分类 JSON 加载）、4（多分类）。
 */
class ProductCatalogTest {

    private static final Gson GSON = new Gson();

    private static Map<String, JsonElement> one(String id, String json) {
        return Map.of(id, JsonParser.parseString(json));
    }

    @Test
    void loadsCategoriesFromJson() {
        Map<String, JsonElement> categories = one("wood", """
                {
                  "id": "wood",
                  "name": "木材",
                  "icon": "minecraft:oak_planks",
                  "sort": 10,
                  "enabled": true
                }
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(categories, Map.of());
        assertEquals(1, catalog.categoryCount());
        assertTrue(catalog.category("wood").isPresent());
        assertEquals("木材", catalog.category("wood").get().name());
        assertEquals("minecraft:oak_planks", catalog.category("wood").get().iconItemId());
        assertEquals(10, catalog.category("wood").get().sort());
    }

    @Test
    void loadsProductsFromJson() {
        Map<String, JsonElement> products = one("oak_planks", """
                {
                  "id": "oak_planks",
                  "item": "minecraft:oak_planks",
                  "categories": ["wood", "building"],
                  "currency": "virtual_coins",
                  "unitPrice": 2,
                  "bulkSize": 64,
                  "stock": { "mode": "infinite" },
                  "displayName": "橡木板",
                  "description": "常用的基础建筑材料",
                  "enabled": true,
                  "sort": 10
                }
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(1, catalog.productCount());
        Product product = catalog.product("oak_planks").orElseThrow();
        assertEquals("minecraft:oak_planks", product.itemId());
        assertEquals(2, product.unitPrice());
        assertEquals(64, product.bulkSize());
        assertEquals(StockMode.INFINITE, product.stockMode());
        assertEquals("橡木板", product.displayName());
        assertTrue(product.enabled());
    }

    @Test
    void productBelongsToMultipleCategories() {
        Map<String, JsonElement> products = one("p", """
                {"id":"p","item":"minecraft:oak_planks","categories":["wood","building","redstone"],"unitPrice":1}
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        Product product = catalog.product("p").orElseThrow();
        assertEquals(3, product.categories().size());
        assertTrue(product.inCategory("wood"));
        assertTrue(product.inCategory("building"));
        assertTrue(product.inCategory("redstone"));
        assertFalse(product.inCategory("glass"));
        // "全部" 分类匹配所有商品
        assertTrue(product.inCategory(Category.ALL_ID));
    }

    @Test
    void invalidProductsAreSkipped() {
        Map<String, JsonElement> products = Map.of(
                "missing_item", JsonParser.parseString("{\"unitPrice\":2}"),
                "missing_price", JsonParser.parseString("{\"item\":\"minecraft:stone\"}"),
                "bad_price", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":-3}"),
                "ok", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":3}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(1, catalog.productCount());
        assertTrue(catalog.product("ok").isPresent());
    }

    @Test
    void sortingUsesSortFieldNotFileName() {
        Map<String, JsonElement> products = Map.of(
                "zzz", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":1,\"sort\":5}"),
                "aaa", JsonParser.parseString("{\"item\":\"minecraft:glass\",\"unitPrice\":1,\"sort\":1}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals("aaa", catalog.products().get(0).id());
        assertEquals("zzz", catalog.products().get(1).id());
    }

    @Test
    void finiteStockParsed() {
        Map<String, JsonElement> products = one("g", """
                {"id":"g","item":"minecraft:glowstone","unitPrice":30,"stock":{"mode":"finite","quantity":5000}}
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        Product product = catalog.product("g").orElseThrow();
        assertEquals(StockMode.FINITE, product.stockMode());
        assertEquals(5000, product.stockQuantity());
    }

    @Test
    void effectiveNameFallsBackToItemId() {
        Map<String, JsonElement> products = one("x", """
                {"id":"x","item":"minecraft:stone","unitPrice":1}
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals("minecraft:stone", catalog.product("x").orElseThrow().effectiveName());
    }
}
