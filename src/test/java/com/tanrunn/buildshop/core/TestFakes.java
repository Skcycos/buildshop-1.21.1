package com.tanrunn.buildshop.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试用的钱包/发货/幂等假实现（纯 Java，不依赖 Minecraft）。 */
public final class TestFakes {

    private TestFakes() {
    }

    /** 账户型钱包：余额以最小单位记录。 */
    public static final class FakeWallet implements Wallet {
        private long balance;
        private int withdrawCalls;
        private int refundCalls;
        private long lastWithdrawAmount;
        private final java.util.Set<String> withdrawnRequestIds = new java.util.HashSet<>();

        public FakeWallet(long balance) {
            this.balance = balance;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public boolean canWithdraw(long amount) {
            return balance >= amount;
        }

        @Override
        public boolean withdraw(long amount, String requestId) {
            withdrawCalls++;
            lastWithdrawAmount = amount;
            if (amount < 0 || balance < amount) return false;
            balance -= amount;
            withdrawnRequestIds.add(requestId);
            return true;
        }

        @Override
        public boolean refund(long amount, String requestId) {
            refundCalls++;
            balance += amount;
            return true;
        }

        public int withdrawCalls() {
            return withdrawCalls;
        }

        public int refundCalls() {
            return refundCalls;
        }

        public long lastWithdrawAmount() {
            return lastWithdrawAmount;
        }

        public boolean withdrew(String requestId) {
            return withdrawnRequestIds.contains(requestId);
        }
    }

    /** 物品钱包：余额 = 物品数量；扣款移除、退款返还。 */
    public static final class ItemWallet implements Wallet {
        private long items;

        public ItemWallet(long items) {
            this.items = items;
        }

        @Override
        public long balance() {
            return items;
        }

        @Override
        public boolean canWithdraw(long amount) {
            return items >= amount;
        }

        @Override
        public boolean withdraw(long amount, String requestId) {
            if (items < amount) return false;
            items -= amount;
            return true;
        }

        @Override
        public boolean refund(long amount, String requestId) {
            items += amount;
            return true;
        }

        public long items() {
            return items;
        }
    }

    /** 带容量上限的发货器；可模拟发货失败（capacity 为负时）。 */
    public static final class FakeDispatcher implements ItemDispatcher {
        private final int capacity;
        private int dispensed;
        private int canCalls;
        private int dispenseCalls;
        private boolean failDelivery;

        public FakeDispatcher(int capacity) {
            this.capacity = capacity;
        }

        public FakeDispatcher failDelivery() {
            this.failDelivery = true;
            return this;
        }

        @Override
        public boolean canDispense(int quantity) {
            canCalls++;
            return capacity >= quantity;
        }

        @Override
        public boolean dispense(int quantity) {
            dispenseCalls++;
            if (failDelivery) return false;
            if (capacity < dispensed + quantity) return false;
            dispensed += quantity;
            return true;
        }

        public int dispensed() {
            return dispensed;
        }

        public int canCalls() {
            return canCalls;
        }

        public int dispenseCalls() {
            return dispenseCalls;
        }
    }

    /** 内存幂等存储。 */
    public static final class MapIdempotency implements IdempotencyStore {
        private final Map<String, PurchaseResult> map = new LinkedHashMap<>();

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public void putIfAbsent(String key, PurchaseResult result) {
            map.putIfAbsent(key, result);
        }

        @Override
        public PurchaseResult get(String key) {
            return map.get(key);
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }
    }
}
