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

    @Test
    void malformedNumericFieldsDoNotCrashTheWholeReload() {
        Map<String, JsonElement> products = Map.of(
                "bad_price", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":\"abc\"}"),
                "bad_stock", JsonParser.parseString(
                        "{\"item\":\"minecraft:glowstone\",\"unitPrice\":30,\"stock\":{\"mode\":\"finite\",\"quantity\":\"oops\"}}"),
                "neg_stock", JsonParser.parseString(
                        "{\"item\":\"minecraft:glowstone\",\"unitPrice\":30,\"stock\":{\"mode\":\"finite\",\"quantity\":-5}}"),
                "neg_bulk", JsonParser.parseString(
                        "{\"item\":\"minecraft:stone\",\"unitPrice\":1,\"bulkSize\":-3}"),
                "bad_item", JsonParser.parseString("{\"item\":\"not a resource id\",\"unitPrice\":1}"),
                "ok", JsonParser.parseString("{\"item\":\"minecraft:stone\",\"unitPrice\":3}")
        );
        // 单条畸形数据不得导致整个目录重载崩溃：正确条目照常加载。
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(1, catalog.productCount());
        assertTrue(catalog.product("ok").isPresent());
    }

    @Test
    void productIdComesFromJsonFieldNotResourceKey() {
        Map<String, JsonElement> products = Map.of(
                "some:path/to/comparator", JsonParser.parseString(
                        "{\"id\":\"comparator\",\"item\":\"minecraft:comparator\",\"unitPrice\":12}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(1, catalog.productCount());
        assertTrue(catalog.product("comparator").isPresent(), "ID 应取 JSON 的 id 字段");
        assertFalse(catalog.product("some:path/to/comparator").isPresent());
    }

    @Test
    void duplicateProductIdsKeepFirstAndDoNotCrash() {
        Map<String, JsonElement> products = Map.of(
                "ns1:products/comparator", JsonParser.parseString(
                        "{\"id\":\"comparator\",\"item\":\"minecraft:comparator\",\"unitPrice\":12}"),
                "ns2:products/comparator", JsonParser.parseString(
                        "{\"id\":\"comparator\",\"item\":\"minecraft:stone\",\"unitPrice\":99}"),
                "ok", JsonParser.parseString("{\"id\":\"ok\",\"item\":\"minecraft:stone\",\"unitPrice\":1}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(2, catalog.productCount(), "重复 ID 只保留一个，其余条目照常加载");
        assertTrue(catalog.product("comparator").isPresent(), "重复 ID 保留其中一条且不崩溃");
        assertTrue(catalog.product("ok").isPresent());
    }

    @Test
    void unknownCategoryReferenceDoesNotCrash() {
        Map<String, JsonElement> categories = one("wood", """
                {"id":"wood","name":"木材"}
                """);
        Map<String, JsonElement> products = one("p", """
                {"id":"p","item":"minecraft:oak_planks","categories":["wood","does_not_exist"],"unitPrice":1}
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(categories, products);
        assertEquals(1, catalog.productCount(), "未知分类引用只警告，不中断加载");
    }

    @Test
    void finiteStockQuantityZeroIsValid() {
        Map<String, JsonElement> products = one("z", """
                {"id":"z","item":"minecraft:glowstone","unitPrice":30,"stock":{"mode":"finite","quantity":0}}
                """);
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(StockMode.FINITE, catalog.product("z").orElseThrow().stockMode());
        assertEquals(0, catalog.product("z").orElseThrow().stockQuantity());
    }

    @Test
    void categoryIdComesFromJsonField() {
        Map<String, JsonElement> categories = Map.of(
                "other:path/light", JsonParser.parseString("{\"id\":\"light\",\"name\":\"光源\"}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(categories, Map.of());
        assertTrue(catalog.category("light").isPresent());
    }

    @Test
    void nonBooleanEnabledDoesNotCrashTheReload() {
        // 回归：enabled 为对象/数组等非法类型时，单条商品进入错误处理，
        // 不能抛 UnsupportedOperationException 终止整个目录加载。
        Map<String, JsonElement> products = Map.of(
                "bad_enabled", JsonParser.parseString(
                        "{\"id\":\"bad_enabled\",\"item\":\"minecraft:stone\",\"unitPrice\":1,\"enabled\":{}}"),
                "bad_enabled_arr", JsonParser.parseString(
                        "{\"id\":\"bad_enabled_arr\",\"item\":\"minecraft:stone\",\"unitPrice\":1,\"enabled\":[1,2]}"),
                "ok", JsonParser.parseString(
                        "{\"id\":\"ok\",\"item\":\"minecraft:stone\",\"unitPrice\":3}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(Map.of(), products);
        assertEquals(3, catalog.productCount(), "非法 enabled 不得终止其他商品加载");
        assertTrue(catalog.product("bad_enabled").orElseThrow().enabled(), "非法 enabled 按未配置（启用）处理");
        assertTrue(catalog.product("ok").isPresent());
    }

    @Test
    void categoryWithNonBooleanEnabledDoesNotCrash() {
        Map<String, JsonElement> categories = Map.of(
                "weird", JsonParser.parseString("{\"id\":\"weird\",\"name\":\"怪\",\"enabled\":{}}"),
                "normal", JsonParser.parseString("{\"id\":\"normal\",\"name\":\"正常\"}")
        );
        ProductCatalog catalog = ProductCatalog.fromJson(categories, Map.of());
        assertEquals(2, catalog.categoryCount(), "非法 enabled 分类不得终止加载");
        assertTrue(catalog.category("weird").orElseThrow().enabled());
    }
}
