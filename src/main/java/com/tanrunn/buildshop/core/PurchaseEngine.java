package com.tanrunn.buildshop.core;

/**
 * 服务端购买事务（纯逻辑，可在单元测试中完整验证）。
 *
 * <p>事务顺序（与设计指南第十一节一致）：检查商品 → 检查启用 → 计算数量 →
 * 检查上限 → 检查库存 → 检查背包空间 → 检查余额 → 扣款 → 发货 → 扣库存 →
 * 任何一步失败都不扣款不发货不扣库存；扣款后发货失败必须退款。</p>
 *
 * <p>引擎不接触 Minecraft：玩家钱包、背包与库存通过接口注入。</p>
 */
public final class PurchaseEngine {

    private final ProductCatalog catalog;
    private final StockStore stock;
    private final IdempotencyStore idempotency;
    private final int serverMaxPerRequest;

    public PurchaseEngine(ProductCatalog catalog, StockStore stock, IdempotencyStore idempotency, int serverMaxPerRequest) {
        this.catalog = catalog;
        this.stock = stock;
        this.idempotency = idempotency;
        this.serverMaxPerRequest = Math.max(1, serverMaxPerRequest);
    }

    public PurchaseResult execute(PurchaseRequest request, Wallet wallet, ItemDispatcher dispatcher) {
        String idemKey = request.requestId();
        PurchaseResult cached = idempotency.get(idemKey);
        if (cached != null) {
            return cached;
        }

        PurchaseResult result = run(request, wallet, dispatcher);
        idempotency.putIfAbsent(idemKey, result);
        return result;
    }

    private PurchaseResult run(PurchaseRequest request, Wallet wallet, ItemDispatcher dispatcher) {
        Product product = catalog.product(request.productId()).orElse(null);
        if (product == null) {
            return PurchaseResult.fail(PurchaseFailure.PRODUCT_NOT_FOUND);
        }
        if (!product.enabled()) {
            return PurchaseResult.fail(PurchaseFailure.PRODUCT_DISABLED);
        }
        if (wallet == null || dispatcher == null) {
            return PurchaseResult.fail(PurchaseFailure.NO_CURRENCY_PROVIDER);
        }

        QuantityPlan plan = planQuantity(product, request, wallet, dispatcher);
        if (plan.quantity <= 0) {
            return PurchaseResult.fail(plan.failure);
        }

        long totalPrice = mulChecked(product.unitPrice(), plan.quantity);
        if (totalPrice < 0) {
            return PurchaseResult.fail(PurchaseFailure.PRICE_OVERFLOW);
        }

        if (product.stockMode() == StockMode.FINITE) {
            int remaining = stock.remaining(product.id());
            if (remaining < 0 || remaining < plan.quantity) {
                return PurchaseResult.fail(PurchaseFailure.INSUFFICIENT_STOCK);
            }
        }

        if (!wallet.canWithdraw(totalPrice)) {
            return PurchaseResult.fail(PurchaseFailure.INSUFFICIENT_BALANCE);
        }
        if (!dispatcher.canDispense(plan.quantity)) {
            return PurchaseResult.fail(PurchaseFailure.INSUFFICIENT_INVENTORY);
        }

        if (!wallet.withdraw(totalPrice, request.requestId())) {
            return PurchaseResult.fail(PurchaseFailure.WITHDRAW_FAILED);
        }

        if (!dispatcher.dispense(plan.quantity)) {
            wallet.refund(totalPrice, request.requestId());
            return PurchaseResult.fail(PurchaseFailure.DELIVERY_FAILED);
        }

        if (product.stockMode() == StockMode.FINITE) {
            boolean consumed = stock.consume(product.id(), plan.quantity);
            if (!consumed) {
                wallet.refund(totalPrice, request.requestId());
                return PurchaseResult.fail(PurchaseFailure.INSUFFICIENT_STOCK);
            }
        }

        return PurchaseResult.success(plan.quantity, totalPrice);
    }

    private QuantityPlan planQuantity(Product product, PurchaseRequest request, Wallet wallet, ItemDispatcher dispatcher) {
        switch (request.mode()) {
            case SINGLE:
                return new QuantityPlan(1, null);
            case BULK:
                return new QuantityPlan(product.effectiveBulkSize(), null);
            case CUSTOM:
                if (request.requestedQuantity() <= 0 || request.requestedQuantity() > serverMaxPerRequest) {
                    return new QuantityPlan(0, PurchaseFailure.INVALID_QUANTITY);
                }
                if (!dispatcher.canDispense(request.requestedQuantity())) {
                    return new QuantityPlan(0, PurchaseFailure.INSUFFICIENT_INVENTORY);
                }
                return new QuantityPlan(request.requestedQuantity(), null);
            case MAX:
                return planMax(product, wallet, dispatcher);
            default:
                return new QuantityPlan(0, PurchaseFailure.INVALID_QUANTITY);
        }
    }

    /** Ctrl+左键：余额/库存/背包/服务端上限共同约束下的最大数量。 */
    private QuantityPlan planMax(Product product, Wallet wallet, ItemDispatcher dispatcher) {
        long balance = wallet.balance();
        int byBalance = product.unitPrice() > 0 ? (int) Math.min(balance / product.unitPrice(), Integer.MAX_VALUE) : 0;
        if (byBalance <= 0) {
            return new QuantityPlan(0, PurchaseFailure.INSUFFICIENT_BALANCE);
        }

        int quantity = Math.min(byBalance, serverMaxPerRequest);
        PurchaseFailure binding = PurchaseFailure.INSUFFICIENT_BALANCE;

        if (product.stockMode() == StockMode.FINITE) {
            int remaining = stock.remaining(product.id());
            if (remaining < 0) {
                return new QuantityPlan(0, PurchaseFailure.INSUFFICIENT_STOCK);
            }
            if (remaining < quantity) {
                quantity = remaining;
                binding = PurchaseFailure.INSUFFICIENT_STOCK;
            }
        }

        int bySpace = dispatcher.canDispense(Integer.MAX_VALUE)
                ? Integer.MAX_VALUE
                : maxDispenseable(dispatcher);
        if (bySpace < quantity) {
            quantity = bySpace;
            binding = PurchaseFailure.INSUFFICIENT_INVENTORY;
        }

        if (quantity <= 0) {
            return new QuantityPlan(0, binding);
        }
        return new QuantityPlan(quantity, null);
    }

    private int maxDispenseable(ItemDispatcher dispatcher) {
        int low = 0;
        int high = serverMaxPerRequest;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (dispatcher.canDispense(mid)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** 溢出安全的乘法：溢出返回 -1。 */
    static long mulChecked(long a, long b) {
        if (a == 0 || b == 0) return 0;
        long result = a * b;
        return result / a == b ? result : -1;
    }

    private record QuantityPlan(int quantity, PurchaseFailure failure) {
    }
}
