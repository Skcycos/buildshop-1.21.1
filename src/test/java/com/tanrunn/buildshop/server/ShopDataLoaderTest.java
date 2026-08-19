package com.tanrunn.buildshop.server;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShopDataLoader#acceptEntry} 纯逻辑测试：
 * 内置示例数据包开关关闭时忽略本 mod 命名空间，服务端自备数据包不受影响。
 */
class ShopDataLoaderTest {

    @Test
    void builtinEnableAcceptsEverything() {
        assertTrue(ShopDataLoader.acceptEntry(true,
                ResourceLocation.fromNamespaceAndPath("buildshop", "building_shop/products/oak_stairs")));
        assertTrue(ShopDataLoader.acceptEntry(true,
                ResourceLocation.fromNamespaceAndPath("minecraft", "building_shop/products/custom")));
    }

    @Test
    void builtinDisabledRejectsModNamespace() {
        assertFalse(ShopDataLoader.acceptEntry(false,
                ResourceLocation.fromNamespaceAndPath("buildshop", "building_shop/products/oak_stairs")));
        assertFalse(ShopDataLoader.acceptEntry(false,
                ResourceLocation.fromNamespaceAndPath("buildshop", "building_shop/categories/basic")));
    }

    @Test
    void builtinDisabledKeepsServerSuppliedDatapacks() {
        assertTrue(ShopDataLoader.acceptEntry(false,
                ResourceLocation.fromNamespaceAndPath("minecraft", "building_shop/products/custom")));
        assertTrue(ShopDataLoader.acceptEntry(false,
                ResourceLocation.fromNamespaceAndPath("my_server", "building_shop/categories/shop_cat")));
    }

    @Test
    void nullKeyIsRejected() {
        assertFalse(ShopDataLoader.acceptEntry(true, null));
        assertFalse(ShopDataLoader.acceptEntry(false, null));
    }
}