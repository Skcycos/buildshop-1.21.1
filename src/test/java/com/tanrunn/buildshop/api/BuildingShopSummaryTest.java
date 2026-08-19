package com.tanrunn.buildshop.api;

import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link BuildingShopApi#summary} 与 {@link BuildingShopSummary} 的测试。
 *
 * <p>真实服务端路径（Config 已加载、目录已应用、货币已注册）无法在单测中构造；
 * 这里覆盖 null/线程守卫、缺失默认货币提供者的稳定降级（纯转换）、record 非 null
 * 约束与方法签名反射守卫。</p>
 */
class BuildingShopSummaryTest {

    @Test
    void recordStringsAreNeverNull() {
        BuildingShopSummary summary = new BuildingShopSummary(true, 3, null, null, 0, null);
        assertEquals("", summary.defaultCurrencyId());
        assertEquals("", summary.defaultCurrencyName());
        assertEquals("", summary.formattedDefaultBalance());
        assertTrue(summary.shopEnabled());
        assertEquals(3, summary.enabledProductCount());
    }

    @Test
    void summaryRejectsNullPlayer() {
        assertThrows(IllegalArgumentException.class, () -> BuildingShopApi.summary(null));
    }

    @Test
    void summaryRejectsPlayerWithoutServerThread() {
        // mock 玩家没有服务端上下文（server 为 null），走非服务端主线程守卫。
        ServerPlayer player = mock(ServerPlayer.class);
        assertThrows(IllegalStateException.class, () -> BuildingShopApi.summary(player));
    }

    @Test
    void missingDefaultCurrencyProviderDegradesSafely() {
        BuildingShopSummary summary = BuildingShopApi.buildSummary(true, 5, "virtual_coins", null, null);
        assertTrue(summary.shopEnabled());
        assertEquals(5, summary.enabledProductCount());
        assertEquals("virtual_coins", summary.defaultCurrencyId());
        assertEquals("virtual_coins", summary.defaultCurrencyName());
        assertEquals(0L, summary.defaultBalance());
        assertEquals("0", summary.formattedDefaultBalance());
    }

    @Test
    void blankCurrencyIdWithMissingProviderStaysStable() {
        BuildingShopSummary summary = BuildingShopApi.buildSummary(false, 0, "", null, null);
        assertTrue(!summary.shopEnabled());
        assertEquals(0, summary.enabledProductCount());
        assertEquals("", summary.defaultCurrencyId());
        assertEquals("", summary.defaultCurrencyName());
        assertEquals(0L, summary.defaultBalance());
        assertEquals("0", summary.formattedDefaultBalance());
    }

    @Test
    void providerValuesAreUsedWhenPresent() {
        ShopCurrencyProvider provider = new ShopCurrencyProvider() {
            @Override
            public String id() {
                return "virtual_coins";
            }

            @Override
            public String displayName() {
                return "金币";
            }

            @Override
            public long balance(ServerPlayer player) {
                return 42L;
            }

            @Override
            public boolean canWithdraw(ServerPlayer player, long amount) {
                return true;
            }

            @Override
            public PaymentResult withdraw(ServerPlayer player, long amount, String reason, String requestId) {
                return PaymentResult.ok();
            }

            @Override
            public PaymentResult refund(ServerPlayer player, long amount, String reason, String requestId) {
                return PaymentResult.ok();
            }

            @Override
            public String format(long amount) {
                return amount + " 金币";
            }
        };
        BuildingShopSummary summary = BuildingShopApi.buildSummary(true, 2, "virtual_coins", provider, null);
        assertEquals("金币", summary.defaultCurrencyName());
        assertEquals(42L, summary.defaultBalance());
        assertEquals("42 金币", summary.formattedDefaultBalance());
    }

    @Test
    void summarySignatureIsPublicStaticReturningPublicRecord() throws Exception {
        Method method = BuildingShopApi.class.getMethod("summary", ServerPlayer.class);
        assertTrue(Modifier.isPublic(method.getModifiers()), "summary 必须 public");
        assertTrue(Modifier.isStatic(method.getModifiers()), "summary 必须 static");
        Class<?> returnType = method.getReturnType();
        assertTrue(returnType.isRecord(), "summary 必须返回 record");
        assertTrue(Modifier.isPublic(returnType.getModifiers()), "返回 record 必须 public");
        assertEquals("BuildingShopSummary", returnType.getSimpleName());
    }
}
