package com.tanrunn.buildshop.network;

import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseRequestPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.RequestSyncPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.ShopDisabledPayload;
import com.tanrunn.buildshop.server.ShopServer;
import net.neoforged.neoforge.network.PacketDistributor;
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
            // 数量为负/超限由 ShopServer 与 PurchaseEngine 校验；enabled=false 时服务端直接拒绝伪造购买请求。
            ShopServer.INSTANCE.purchase(player, payload.productId(), mode, payload.requestedQuantity(), payload.requestId());
        });
    }

    public static void handleRequestSync(RequestSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                if (!Config.ENABLED.get() && !player.hasPermissions(2)) {
                    PacketDistributor.sendToPlayer(player, new ShopDisabledPayload());
                    return;
                }
                ShopServer.INSTANCE.syncTo(player);
            }
        });
    }
}
