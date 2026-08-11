package com.tanrunn.buildshop.core;

/**
 * 发货抽象（纯接口）：把商品放进玩家背包。
 *
 * <p>服务端适配到真实背包；核心购买引擎只依赖此接口。</p>
 */
public interface ItemDispatcher {

    /** 能否容纳恰好 {@code quantity} 个物品（整笔检查，不允许部分放入）。 */
    boolean canDispense(int quantity);

    /**
     * 发货 {@code quantity} 个物品。
     *
     * @return true 表示全部放入成功；false 表示未放入任何物品（引擎随后退款）。
     */
    boolean dispense(int quantity);
}
