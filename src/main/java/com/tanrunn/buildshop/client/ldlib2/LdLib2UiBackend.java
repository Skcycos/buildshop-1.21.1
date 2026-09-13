package com.tanrunn.buildshop.client.ldlib2;

import com.tanrunn.buildshop.client.ClientShopState;
import com.tanrunn.buildshop.client.UiBackend;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;

/** LDLib2 adapter. This class is loaded only when the backend manager sees mod id {@code ldlib2}. */
public final class LdLib2UiBackend implements UiBackend {
    private final ClientShopState state = new ClientShopState();
    private final LdLib2ShopScreenController shop = new LdLib2ShopScreenController(state, this::openDashboard);
    private final LdLib2PurchaseDashboardController dashboard =
            new LdLib2PurchaseDashboardController(state, shop::open);

    @Override
    public String id() {
        return "ldlib2";
    }

    @Override
    public void initialize() {
        // The existing custom payload channel remains the source of truth. LDLib2 only hosts the
        // client screen, so no server-side Menu/RPC registration is needed.
    }

    @Override
    public void openShop() {
        shop.open();
    }

    @Override
    public void openDashboard() {
        dashboard.open();
    }

    @Override
    public void applySync(SyncShopPayload payload) {
        state.applySync(payload);
        shop.refreshFromState();
        dashboard.refreshFromState();
    }

    @Override
    public void applyPurchaseResult(PurchaseResultPayload payload) {
        shop.applyPurchaseResult(payload);
        dashboard.refreshFromState();
    }

    @Override
    public void applyShopDisabled() {
        shop.applyShopDisabled();
    }
}
