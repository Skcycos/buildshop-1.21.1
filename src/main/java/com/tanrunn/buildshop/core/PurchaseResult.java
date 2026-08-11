package com.tanrunn.buildshop.core;

/** 购买结果（纯数据，可由服务端回显到客户端）。 */
public final class PurchaseResult {

    public static final PurchaseResult EMPTY = new PurchaseResult(false, PurchaseFailure.UNKNOWN, 0, 0, "");

    private final boolean success;
    private final PurchaseFailure failure;
    private final int quantity;
    private final long totalPrice;
    private final String messageKey;

    private PurchaseResult(boolean success, PurchaseFailure failure, int quantity, long totalPrice, String messageKey) {
        this.success = success;
        this.failure = failure;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.messageKey = messageKey;
    }

    public static PurchaseResult success(int quantity, long totalPrice) {
        return new PurchaseResult(true, null, quantity, totalPrice, "buildshop.result.success");
    }

    public static PurchaseResult fail(PurchaseFailure failure) {
        return new PurchaseResult(false, failure, 0, 0, "buildshop.result." + failure.name().toLowerCase());
    }

    public static PurchaseResult fail(PurchaseFailure failure, String messageKey) {
        return new PurchaseResult(false, failure, 0, 0, messageKey);
    }

    public boolean success() {
        return success;
    }

    public PurchaseFailure failure() {
        return failure;
    }

    public int quantity() {
        return quantity;
    }

    public long totalPrice() {
        return totalPrice;
    }

    public String messageKey() {
        return messageKey;
    }

    @Override
    public String toString() {
        return success ? "PurchaseResult{ok, qty=" + quantity + ", price=" + totalPrice + "}"
                : "PurchaseResult{fail: " + failure + "}";
    }
}
