package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.core.Category;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseRequestPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Shared client state used by the LDLib2 screens (and kept independent of either UI library). */
public final class ClientShopState {
    public enum SortMode {
        DEFAULT, PRICE_ASC, PRICE_DESC
    }

    public record PurchaseFeedback(boolean success, int quantity, long totalPrice, String messageKey) {
    }

    private final ShopClientModel model = new ShopClientModel();
    private final Map<String, ProductDto> pendingPurchases = new LinkedHashMap<>();

    public ShopClientModel model() {
        return model;
    }

    public void applySync(SyncShopPayload payload) {
        model.applySync(payload);
    }

    public List<ProductDto> filteredProducts(String category, String search, SortMode sort) {
        String selected = category == null ? Category.ALL_ID : category;
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<ProductDto> result = new ArrayList<>();
        for (ProductDto product : model.products()) {
            if (!Category.ALL_ID.equals(selected) && !product.categories().contains(selected)) continue;
            if (!query.isEmpty()
                    && !product.displayName().toLowerCase(Locale.ROOT).contains(query)
                    && !product.id().toLowerCase(Locale.ROOT).contains(query)
                    && (product.description() == null || !product.description().toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            result.add(product);
        }
        if (sort == SortMode.PRICE_ASC) {
            result.sort(Comparator.comparingLong(ProductDto::unitPrice));
        } else if (sort == SortMode.PRICE_DESC) {
            result.sort(Comparator.comparingLong(ProductDto::unitPrice).reversed());
        } else {
            result.sort(Comparator.comparingInt(ProductDto::sort).thenComparing(ProductDto::id));
        }
        return result;
    }

    public ProductDto product(String id) {
        return model.product(id);
    }

    public void requestPurchase(String productId, PurchaseMode mode, int quantity) {
        String requestId = UUID.randomUUID().toString();
        ProductDto product = model.product(productId);
        if (product != null) {
            pendingPurchases.put(requestId, product);
            while (pendingPurchases.size() > 32) {
                pendingPurchases.remove(pendingPurchases.keySet().iterator().next());
            }
        }
        PacketDistributor.sendToServer(new PurchaseRequestPayload(productId, (byte) mode.ordinal(), quantity, requestId));
    }

    public PurchaseFeedback applyPurchaseResult(PurchaseResultPayload payload) {
        if (payload == null) return new PurchaseFeedback(false, 0, 0, "buildshop.result.unknown");
        ProductDto product = pendingPurchases.remove(payload.requestId());
        if (payload.success() && product != null && payload.quantity() > 0) {
            long gameTime = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
            ClientPurchaseHistory.INSTANCE.add(new ClientPurchaseRecord(
                    gameTime / 24_000L,
                    gameTime,
                    product.id(),
                    product.displayName(),
                    primaryCategoryName(product),
                    model.currencyName(product.currency()),
                    payload.quantity(),
                    payload.totalPrice()));
        }
        model.applyBalanceUpdates(payload.balances(), payload.balanceAmounts());
        if (payload.stockUpdates() != null) {
            payload.stockUpdates().forEach(model::applyStockUpdate);
        }
        return new PurchaseFeedback(payload.success(), payload.quantity(), payload.totalPrice(),
                payload.messageKey() == null ? "buildshop.result.unknown" : payload.messageKey());
    }

    public String primaryCategoryName(ProductDto product) {
        if (product == null || product.categories().isEmpty()) return "未分类";
        String id = product.categories().get(0);
        for (CategoryDto category : model.categories()) {
            if (category.id().equals(id)) return category.name();
        }
        return id;
    }

    public boolean deliveryAvailable(ProductDto product) {
        if (product == null) return false;
        if (product.isEntityProduct()) {
            ResourceLocation id = ResourceLocation.tryParse(product.entityId());
            return id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id);
        }
        ResourceLocation id = ResourceLocation.tryParse(product.itemId());
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }
}
