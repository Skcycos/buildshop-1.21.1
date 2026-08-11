package com.tanrunn.buildshop.core;

/**
 * 请求幂等存储（纯接口）。
 *
 * <p>服务端按玩家作用域保存（{@code playerId:requestId}）；相同 requestId 的重复请求
 * 返回第一次的结果，绝不重复扣款。</p>
 */
public interface IdempotencyStore {

    /** 之前是否处理过该请求。 */
    boolean contains(String key);

    /** 保存结果（不覆盖已有条目）。 */
    void putIfAbsent(String key, PurchaseResult result);

    /** 读取已有结果；不存在返回 null。 */
    PurchaseResult get(String key);

    /** 移除（bounded 存储淘汰旧条目时调用）。 */
    void remove(String key);
}
