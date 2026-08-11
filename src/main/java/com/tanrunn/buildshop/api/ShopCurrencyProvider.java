package com.tanrunn.buildshop.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * 商店货币提供者。
 *
 * <p>不要把商店逻辑绑定到某一个具体经济 Mod：外部 Mod 可以在初始化时
 * 通过 {@link BuildingShopApi#registerCurrencyProvider} 注册自己的实现。</p>
 *
 * <p>金额使用整数最小单位；每种货币由自己的 Provider 负责解释单位。
 * 所有方法必须在服务端主线程调用。</p>
 */
public interface ShopCurrencyProvider {

    /** 提供者唯一 ID（商品 JSON 的 {@code currency} 字段）。 */
    String id();

    /** 显示名称（如 "金币"、"绿宝石"）。 */
    String displayName();

    /** 查询玩家余额。 */
    long balance(ServerPlayer player);

    /** 是否足够扣除 {@code amount}。 */
    boolean canWithdraw(ServerPlayer player, long amount);

    /**
     * 扣款。成功返回成功结果；失败返回失败结果（不得部分扣款）。
     *
     * @param requestId 幂等键；同一 requestId 重试不得重复扣款。
     */
    PaymentResult withdraw(ServerPlayer player, long amount, String reason, String requestId);

    /** 退款（发货失败回滚）。 */
    PaymentResult refund(ServerPlayer player, long amount, String reason, String requestId);

    /** 金额格式化（显示用）。 */
    String format(long amount);
}
