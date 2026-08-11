package com.tanrunn.buildshop.network;

import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseRequestPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.RequestSyncPayload;
import com.tanrunn.buildshop.server.ShopServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端网络包处理（全部转到服务端主线程执行）。 */
public final class ServerHandler {
    private ServerHandler() {
    }

    public static void handlePurchaseRequest(PurchaseRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            PurchaseMode mode = switch (payload.mode()) {
                case 1 -> PurchaseMode.BULK;
                case 2 -> PurchaseMode.MAX;
                case 3 -> PurchaseMode.CUSTOM;
                default -> PurchaseMode.SINGLE;
            };
            ShopServer.INSTANCE.purchase(player, payload.productId(), mode, payload.requestedQuantity(), payload.requestId());
        });
    }

    public static void handleRequestSync(RequestSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                ShopServer.INSTANCE.syncTo(player);
            }
        });
    }
}
