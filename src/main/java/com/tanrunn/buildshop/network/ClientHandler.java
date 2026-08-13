package com.tanrunn.buildshop.network;

import com.tanrunn.buildshop.client.ShopScreenController;
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
        Minecraft.getInstance().execute(() -> ShopScreenController.INSTANCE.open());
    }

    public static void handleSyncShop(SyncShopPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> ShopScreenController.INSTANCE.applySync(payload));
    }

    public static void handleShopDisabled(ShopDisabledPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> ShopScreenController.INSTANCE.applyShopDisabled());
    }

    public static void handlePurchaseResult(PurchaseResultPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> ShopScreenController.INSTANCE.applyPurchaseResult(payload));
    }
}
