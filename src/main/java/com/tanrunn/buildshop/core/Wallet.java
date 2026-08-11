package com.tanrunn.buildshop.core;

/**
 * 钱包抽象（纯接口）。
 *
 * <p>服务端适配到具体的货币 Provider；核心购买引擎只依赖此接口，便于单元测试。
 * {@code requestId} 用于幂等扣款。</p>
 */
public interface Wallet {

    /** 当前余额（货币 Provider 的最小单位）。 */
    long balance();

    /** 是否足够扣除 {@code amount}。 */
    boolean canWithdraw(long amount);

    /** 扣除 {@code amount}。成功返回 true；失败不得部分扣款。 */
    boolean withdraw(long amount, String requestId);

    /** 退还 {@code amount}（发货失败回滚）。 */
    boolean refund(long amount, String requestId);
}
