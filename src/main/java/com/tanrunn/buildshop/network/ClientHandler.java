package com.tanrunn.buildshop.network;

import com.tanrunn.buildshop.client.UiBackendManager;
import com.tanrunn.buildshop.network.BuildShopNetwork.OpenShopPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.ShopDisabledPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端网络包处理（全部转到客户端线程执行）。 */
public final class ClientHandler {
    private ClientHandler() {
    }

    public static void handleOpenShop(OpenShopPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> {
            UiBackendManager.INSTANCE.setServerBackend(payload.uiBackend());
            UiBackendManager.INSTANCE.openShop();
        });
    }

    public static void handleSyncShop(SyncShopPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> UiBackendManager.INSTANCE.applySync(payload));
    }

    public static void handleShopDisabled(ShopDisabledPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(UiBackendManager.INSTANCE::applyShopDisabled);
    }

    public static void handlePurchaseResult(PurchaseResultPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> UiBackendManager.INSTANCE.applyPurchaseResult(payload));
    }
}
