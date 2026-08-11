package com.tanrunn.buildshop.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖设计指南测试项：25（物品材质加载失败时使用安全占位，不导致客户端崩溃）。
 */
class ItemExpressionUtilTest {

    @Test
    void unavailableItemFallsBackToSafePlaceholder() {
        // 客户端未安装对应 Mod：显示"材质不可用"占位，绝不使用服务端表达式
        assertEquals("minecraft:air", ItemExpressionUtil.resolveExpression("somemod:item", "{id:...}", false));
        assertEquals("minecraft:air", ItemExpressionUtil.resolveExpression("minecraft:stone", "{id:...}", false));
    }

    @Test
    void availableItemUsesServerExpression() {
        String expression = "{\"id\":\"minecraft:oak_planks\",\"count\":1}";
        assertEquals(expression, ItemExpressionUtil.resolveExpression("minecraft:oak_planks", expression, true));
    }

    @Test
    void availableItemWithoutExpressionFallsBackToItemId() {
        assertEquals("minecraft:oak_planks", ItemExpressionUtil.resolveExpression("minecraft:oak_planks", null, true));
        assertEquals("minecraft:oak_planks", ItemExpressionUtil.resolveExpression("minecraft:oak_planks", "", true));
    }

    @Test
    void blankItemIdIsSafe() {
        assertEquals("minecraft:air", ItemExpressionUtil.resolveExpression("", null, true));
        assertEquals("minecraft:air", ItemExpressionUtil.resolveExpression(null, null, true));
    }
}
