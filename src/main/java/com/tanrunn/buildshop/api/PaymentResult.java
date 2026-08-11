package com.tanrunn.buildshop.api;

/** 扣款/退款结果。 */
public record PaymentResult(boolean success, String messageKey) {

    public static PaymentResult ok() {
        return new PaymentResult(true, "");
    }

    public static PaymentResult fail(String messageKey) {
        return new PaymentResult(false, messageKey);
    }
}
