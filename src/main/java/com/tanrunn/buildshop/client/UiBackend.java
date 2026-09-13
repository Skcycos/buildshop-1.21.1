package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;

/**
 * Client UI backend contract. The interface deliberately contains no AUI or LDLib2 types so
 * packet handling and backend selection remain safe when either optional library is absent.
 */
public interface UiBackend {
    String id();

    void initialize();

    void openShop();

    void openDashboard();

    void applySync(SyncShopPayload payload);

    void applyPurchaseResult(PurchaseResultPayload payload);

    void applyShopDisabled();
}
