package com.tanrunn.buildshop.core;

/**
 * 购买模式。
 *
 * <pre>
 * 左键        SINGLE —— 购买 1 个
 * Shift+左键  BULK   —— 购买一组（bulkSize 或物品最大堆叠）
 * Ctrl+左键   MAX    —— 尽可能多购买（余额/库存/背包/服务端上限共同约束）
 * 右键        CUSTOM —— 数量选择窗口（服务端仍重新校验）
 * </pre>
 */
public enum PurchaseMode {
    SINGLE,
    BULK,
    MAX,
    CUSTOM
}
