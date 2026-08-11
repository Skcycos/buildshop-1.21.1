package com.tanrunn.buildshop.core;

/** 物品图标表达式构建（纯逻辑，供客户端 &lt;item&gt; 元素使用）。 */
public final class ItemExpressionUtil {
    private ItemExpressionUtil() {
    }

    /**
     * 生成 &lt;item&gt; 元素文本：
     * <ul>
     *   <li>物品不可用（未安装对应 Mod/注册表缺失）→ {@code minecraft:air} 安全占位</li>
     *   <li>有服务端序列化的完整 SNBT → 直接使用</li>
     *   <li>否则回退为物品 ID</li>
     * </ul>
     */
    public static String resolveExpression(String itemId, String serverExpression, boolean available) {
        if (!available) return "minecraft:air";
        if (serverExpression != null && !serverExpression.isBlank()) return serverExpression;
        return itemId == null || itemId.isBlank() ? "minecraft:air" : itemId;
    }
}
