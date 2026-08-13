package com.tanrunn.buildshop.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 购买事务测试。
 *
 * <p>覆盖设计指南测试项：5/6/7（库存）、8（价格）、9/10/11/12（购买模式）、
 * 13/14/15（余额/背包/退款）、16/17（货币）、18（幂等）、19/20/21/22（安全）。</p>
 */
class PurchaseEngineTest {

    private static final int SERVER_CAP = 2304;
    private static final String REQUEST_ID = "req-1";

    private ProductCatalog catalog;
    private StockStore stock;
    private TestFakes.MapIdempotency idempotency;
    private PurchaseEngine engine;

    @BeforeEach
    void setUp() {
        Map<String, JsonElement> products = Map.of(
                "infinite", JsonParser.parseString("""
                        {"id":"infinite","item":"minecraft:oak_planks","unitPrice":2,"bulkSize":64,"maxStack":64,
                         "stock":{"mode":"infinite"}}
                        """),
                "bulk16", JsonParser.parseString("""
                        {"id":"bulk16","item":"minecraft:comparator","unitPrice":12,"bulkSize":16,"maxStack":64,
                         "stock":{"mode":"infinite"}}
                        """),
                "no_bulk", JsonParser.parseString("""
                        {"id":"no_bulk","item":"minecraft:stone","unitPrice":3,"maxStack":64,"stock":{"mode":"infinite"}}
                        """),
                "finite", JsonParser.parseString("""
                        {"id":"finite","item":"minecraft:glowstone","unitPrice":30,"maxStack":64,
                         "stock":{"mode":"finite","quantity":5000}}
                        """),
                "low_stock", JsonParser.parseString("""
                        {"id":"low_stock","item":"minecraft:lantern","unitPrice":20,"maxStack":64,
                         "stock":{"mode":"finite","quantity":10}}
                        """),
                "expensive", JsonParser.parseString("""
                        {"id":"expensive","item":"minecraft:sea_lantern","unitPrice":1000000,"maxStack":64,
                         "stock":{"mode":"infinite"}}
                        """),
                "disabled", JsonParser.parseString("""
                        {"id":"disabled","item":"minecraft:stone","unitPrice":1,"stock":{"mode":"infinite"},"enabled":false}
                        """)
        );
        catalog = ProductCatalog.fromJson(Map.of(), products);
        stock = new StockStore();
        stock.set("finite", 5000);
        stock.set("low_stock", 10);
        idempotency = new TestFakes.MapIdempotency();
        engine = new PurchaseEngine(catalog, stock, idempotency, SERVER_CAP);
    }

    private TestFakes.FakeWallet wallet(long balance) {
        return new TestFakes.FakeWallet(balance);
    }

    // ---------------------------------------------------------- 基本购买

    @Test
    void singlePurchaseBuysOne() {
        TestFakes.FakeWallet wallet = wallet(100);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.SINGLE, 0, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(1, result.quantity());
        assertEquals(2, result.totalPrice());
        assertEquals(1, dispatcher.dispensed());
        assertEquals(98, wallet.balance());
    }

    @Test
    void unitPriceCalculationIsExact() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 37, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(37, result.quantity());
        assertEquals(2L * 37, result.totalPrice());
    }

    // ---------------------------------------------------------- 购买模式

    @Test
    void shiftClickUsesBulkSize() {
        TestFakes.FakeWallet wallet = wallet(10000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("bulk16", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(16, result.quantity());
        assertEquals(16 * 12L, result.totalPrice());
    }

    @Test
    void shiftClickWithoutBulkSizeUsesMaxStack() {
        TestFakes.FakeWallet wallet = wallet(10000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("no_bulk", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(64, result.quantity());
        assertEquals(3 * 64L, result.totalPrice());
    }

    @Test
    void ctrlClickComputesMaximumAcrossConstraints() {
        // 余额 1000 / 单价 30 → 33；库存 5000 → 33；背包容量 20 → 20；服务端上限 2304 → 20
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(20);
        PurchaseResult result = engine.execute(new PurchaseRequest("finite", PurchaseMode.MAX, 0, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(20, result.quantity());
        assertEquals(5000 - 20, stock.remaining("finite"));
    }

    @Test
    void ctrlClickRespectsServerCap() {
        // 余额与库存都充足，但服务端上限 2304
        TestFakes.FakeWallet wallet = wallet(1_000_000L);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(1_000_000);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.MAX, 0, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(SERVER_CAP, result.quantity());
        assertEquals(2L * SERVER_CAP, result.totalPrice());
    }

    @Test
    void rightClickCustomQuantity() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 42, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(42, result.quantity());
    }

    // ---------------------------------------------------------- 库存

    @Test
    void infiniteStockNeverDecreases() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        engine.execute(new PurchaseRequest("infinite", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        engine.execute(new PurchaseRequest("infinite", PurchaseMode.SINGLE, 0, "req-2"), wallet, dispatcher);
        assertEquals(-1, stock.remaining("infinite"));
    }

    @Test
    void finiteStockDecreases() {
        TestFakes.FakeWallet wallet = wallet(100000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        engine.execute(new PurchaseRequest("finite", PurchaseMode.CUSTOM, 64, REQUEST_ID), wallet, dispatcher);
        assertEquals(5000 - 64, stock.remaining("finite"));
    }

    @Test
    void finiteStockInsufficientFails() {
        TestFakes.FakeWallet wallet = wallet(100000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("low_stock", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.INSUFFICIENT_STOCK, result.failure());
        assertEquals(10, stock.remaining("low_stock"));
        assertEquals(100000, wallet.balance());
        assertEquals(0, dispatcher.dispenseCalls());
    }

    // ---------------------------------------------------------- 余额 / 背包 / 退款

    @Test
    void insufficientBalanceNoDelivery() {
        TestFakes.FakeWallet wallet = wallet(100);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("expensive", PurchaseMode.SINGLE, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.INSUFFICIENT_BALANCE, result.failure());
        assertEquals(0, dispatcher.dispenseCalls());
        assertEquals(0, wallet.withdrawCalls());
    }

    @Test
    void insufficientInventoryNoCharge() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(10);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 20, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.INSUFFICIENT_INVENTORY, result.failure());
        assertEquals(0, wallet.withdrawCalls());
        assertEquals(0, dispatcher.dispenseCalls());
    }

    @Test
    void deliveryFailureRefunds() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP).failDelivery();
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.DELIVERY_FAILED, result.failure());
        assertEquals(1, wallet.refundCalls());
        assertEquals(1000, wallet.balance());
        assertEquals(0, dispatcher.dispensed());
    }

    @Test
    void finiteDeliveryFailureRestoresStockAndRefunds() {
        TestFakes.FakeWallet wallet = wallet(1_000_000L);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP).failDelivery();
        PurchaseResult result = engine.execute(new PurchaseRequest("finite", PurchaseMode.CUSTOM, 64, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.DELIVERY_FAILED, result.failure());
        assertEquals(5000, stock.remaining("finite"), "发货失败必须恢复已扣减的库存");
        assertEquals(1_000_000L, wallet.balance(), "发货失败必须退款");
        assertEquals(0, dispatcher.dispensed(), "不得留下部分商品");
    }

    @Test
    void deliveryFailureWithFailedRefundReportsHonestMessage() {
        TestFakes.FakeWallet wallet = wallet(1000).failRefunds();
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP).failDelivery();
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.BULK, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.DELIVERY_FAILED, result.failure());
        assertEquals("buildshop.result.delivery_failed_refund_failed", result.messageKey(),
                "退款失败时不能无条件告诉玩家已退款");
        assertEquals(1000 - 2 * 64, wallet.balance(), "退款失败时金额不得凭空归还");
    }

    @Test
    void sameRequestIdForDifferentProductsAreIndependentRequests() {
        TestFakes.FakeWallet wallet = wallet(100000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        // 同一 requestId 用于不同商品：互不遮蔽，都正常成交。
        PurchaseResult first = engine.execute(new PurchaseRequest("infinite", PurchaseMode.SINGLE, 0, "shared-id"), wallet, dispatcher);
        PurchaseResult second = engine.execute(new PurchaseRequest("bulk16", PurchaseMode.SINGLE, 0, "shared-id"), wallet, dispatcher);
        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals(2, wallet.withdrawCalls(), "两个不同商品应各扣一次款");
        assertEquals(2, dispatcher.dispenseCalls());
    }

    @Test
    void walletReceivesEngineTransactionFingerprintNotRawRequestId() {
        // 回归：扣款/退款必须携带与引擎幂等语义一致的交易指纹（含商品+模式+数量），
        // 而不是原始 requestId——否则遵守 API 幂等契约的第三方货币 Provider
        // 会把"同 requestId 不同商品"误判为已扣款重放，造成发货但未扣款。
        TestFakes.FakeWallet wallet = wallet(100000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);

        PurchaseRequest request = new PurchaseRequest("infinite", PurchaseMode.SINGLE, 0, "shared-id");
        engine.execute(request, wallet, dispatcher);

        String expected = PurchaseEngine.idempotencyKey(request);
        assertTrue(wallet.withdrew(expected), "钱包必须收到引擎交易指纹而非原始 requestId: " + wallet.lastWithdrawRequestId());
        assertFalse(wallet.withdrew("shared-id"), "钱包不得收到原始 requestId");
    }

    @Test
    void refundUsesSameTransactionFingerprint() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP).failDelivery();
        PurchaseRequest request = new PurchaseRequest("infinite", PurchaseMode.BULK, 0, "req-refund-fp");

        engine.execute(request, wallet, dispatcher);

        String expected = PurchaseEngine.idempotencyKey(request);
        assertTrue(wallet.refundedWith(expected), "退款必须使用与扣款相同的交易指纹");
        assertFalse(wallet.refundedWith("req-refund-fp"), "退款不得使用原始 requestId");
    }

    // ---------------------------------------------------------- 货币

    @Test
    void virtualCurrencyWalletDebits() {
        TestFakes.FakeWallet wallet = wallet(500);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 10, REQUEST_ID), wallet, dispatcher);
        assertEquals(500 - 20, wallet.balance());
        assertEquals(20, wallet.lastWithdrawAmount());
    }

    @Test
    void itemCurrencyWalletDeductsAndRefunds() {
        TestFakes.ItemWallet wallet = new TestFakes.ItemWallet(100);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult ok = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 10, REQUEST_ID), wallet, dispatcher);
        assertTrue(ok.success());
        assertEquals(100 - 20, wallet.items());

        TestFakes.FakeDispatcher failing = new TestFakes.FakeDispatcher(SERVER_CAP).failDelivery();
        TestFakes.ItemWallet wallet2 = new TestFakes.ItemWallet(100);
        PurchaseResult failed = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 10, "req-refund"), wallet2, failing);
        assertFalse(failed.success());
        assertEquals(100, wallet2.items());
    }

    // ---------------------------------------------------------- 幂等 / 安全

    @Test
    void retriedRequestIdNeverDebitsTwice() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult first = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 10, REQUEST_ID), wallet, dispatcher);
        PurchaseResult second = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 10, REQUEST_ID), wallet, dispatcher);
        assertTrue(first.success());
        assertEquals(first, second);
        assertEquals(1, wallet.withdrawCalls());
        assertEquals(1, dispatcher.dispenseCalls());
    }

    @Test
    void unknownProductIdCannotBeBought() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("nope", PurchaseMode.SINGLE, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.PRODUCT_NOT_FOUND, result.failure());
    }

    @Test
    void forgedPriceFromClientHasNoEffect() {
        // 客户端无法传价格：结果价格永远等于服务端单价 × 数量
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 50, REQUEST_ID), wallet, dispatcher);
        assertTrue(result.success());
        assertEquals(2L * 50, result.totalPrice());
        assertEquals(2L * 50, wallet.lastWithdrawAmount());
    }

    @Test
    void forgedQuantityCannotBypassServerLimit() {
        TestFakes.FakeWallet wallet = wallet(10_000_000L);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(10_000_000);
        PurchaseResult result = engine.execute(
                new PurchaseRequest("infinite", PurchaseMode.CUSTOM, SERVER_CAP + 1, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.INVALID_QUANTITY, result.failure());
        assertEquals(0, wallet.withdrawCalls());
    }

    @Test
    void disabledProductCannotBeBought() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        PurchaseResult result = engine.execute(new PurchaseRequest("disabled", PurchaseMode.SINGLE, 0, REQUEST_ID), wallet, dispatcher);
        assertFalse(result.success());
        assertEquals(PurchaseFailure.PRODUCT_DISABLED, result.failure());
    }

    @Test
    void negativeOrZeroCustomQuantityRejected() {
        TestFakes.FakeWallet wallet = wallet(1000);
        TestFakes.FakeDispatcher dispatcher = new TestFakes.FakeDispatcher(SERVER_CAP);
        assertFalse(engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, 0, REQUEST_ID), wallet, dispatcher).success());
        assertFalse(engine.execute(new PurchaseRequest("infinite", PurchaseMode.CUSTOM, -5, "req-neg"), wallet, dispatcher).success());
    }
}
