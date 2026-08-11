package com.tanrunn.buildshop.core;

/** 客户端发起的购买请求。客户端不能发送可信价格/最终数量/最终物品。 */
public record PurchaseRequest(
        String productId,
        PurchaseMode mode,
        int requestedQuantity,
        String requestId
) {
    public PurchaseRequest {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
    }
}
