package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopClientModelCurrencyTest {
    @Test
    void unknownCurrencyUsesShortIdAndIsUnavailable() {
        ShopClientModel model = modelWithKnownCurrency();

        assertFalse(model.currencyAvailable("server_menu:lc_bank_main"));
        assertEquals("lc_bank_main", model.compactCurrencyId("server_menu:lc_bank_main"));
        assertEquals("very_long_cur…",
                model.compactCurrencyId("server_menu:very_long_currency_name"));
    }

    @Test
    void advertisedCurrencyKeepsFriendlyName() {
        ShopClientModel model = modelWithKnownCurrency();

        assertTrue(model.currencyAvailable("virtual_coins"));
        assertEquals("金币", model.currencyLabel("virtual_coins"));
    }

    private static ShopClientModel modelWithKnownCurrency() {
        ShopClientModel model = new ShopClientModel();
        model.applySync(new SyncShopPayload(List.of(), List.of(),
                Map.of("virtual_coins", "100"),
                Map.of("virtual_coins", 100L),
                Map.of("virtual_coins", "金币"),
                "virtual_coins", true));
        return model;
    }
}
