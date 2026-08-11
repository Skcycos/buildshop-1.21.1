package com.tanrunn.buildshop.network;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.core.Category;
import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.StockMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 网络包定义与注册。 */
public final class BuildShopNetwork {
    public static final String CHANNEL = "main";

    private BuildShopNetwork() {
        throw new AssertionError();
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(OpenShopPayload.TYPE, OpenShopPayload.STREAM_CODEC, ClientHandler::handleOpenShop);
        registrar.playToClient(SyncShopPayload.TYPE, SyncShopPayload.STREAM_CODEC, ClientHandler::handleSyncShop);
        registrar.playToClient(PurchaseResultPayload.TYPE, PurchaseResultPayload.STREAM_CODEC, ClientHandler::handlePurchaseResult);
        registrar.playToServer(RequestSyncPayload.TYPE, RequestSyncPayload.STREAM_CODEC, ServerHandler::handleRequestSync);
        registrar.playToServer(PurchaseRequestPayload.TYPE, PurchaseRequestPayload.STREAM_CODEC, ServerHandler::handlePurchaseRequest);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BuildShopMod.MODID, path);
    }

    // ---------------------------------------------------------------- DTO

    public record CategoryDto(String id, String name, String iconExpression, int sort) {
        public static CategoryDto from(Category category) {
            return new CategoryDto(category.id(), category.name(), category.iconItemId(), category.sort());
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(id);
            buf.writeUtf(name == null ? "" : name);
            buf.writeUtf(iconExpression == null ? "" : iconExpression);
            buf.writeVarInt(sort);
        }

        static CategoryDto read(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            String name = buf.readUtf();
            String icon = buf.readUtf();
            int sort = buf.readVarInt();
            return new CategoryDto(id, name, icon.isEmpty() ? null : icon, sort);
        }
    }

    public record ProductDto(
            String id,
            String itemId,
            String itemExpression,
            String displayName,
            String description,
            String currency,
            long unitPrice,
            String formattedPrice,
            int bulkSize,
            int maxStack,
            StockMode stockMode,
            int stockRemaining,
            boolean enabled,
            List<String> categories,
            int sort
    ) {
        public static ProductDto from(Product product, String formattedPrice, int stockRemaining) {
            return new ProductDto(
                    product.id(),
                    product.itemId(),
                    null,
                    product.effectiveName(),
                    product.description() == null ? "" : product.description(),
                    product.currency(),
                    product.unitPrice(),
                    formattedPrice,
                    product.bulkSize(),
                    product.maxStack(),
                    product.stockMode(),
                    stockRemaining,
                    product.enabled(),
                    product.categories(),
                    product.sort()
            );
        }

        public ProductDto withItemExpression(String expression) {
            return new ProductDto(id, itemId, expression, displayName, description, currency, unitPrice,
                    formattedPrice, bulkSize, maxStack, stockMode, stockRemaining, enabled, categories, sort);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(id);
            buf.writeUtf(itemId == null ? "" : itemId);
            buf.writeUtf(itemExpression == null ? "" : itemExpression);
            buf.writeUtf(displayName == null ? "" : displayName);
            buf.writeUtf(description == null ? "" : description);
            buf.writeUtf(currency == null ? "" : currency);
            buf.writeLong(unitPrice);
            buf.writeUtf(formattedPrice == null ? "" : formattedPrice);
            buf.writeVarInt(bulkSize);
            buf.writeVarInt(maxStack);
            buf.writeBoolean(stockMode == StockMode.FINITE);
            buf.writeVarInt(stockRemaining);
            buf.writeBoolean(enabled);
            buf.writeCollection(categories, FriendlyByteBuf::writeUtf);
            buf.writeVarInt(sort);
        }

        static ProductDto read(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            String itemId = buf.readUtf();
            String itemExpression = buf.readUtf();
            String displayName = buf.readUtf();
            String description = buf.readUtf();
            String currency = buf.readUtf();
            long unitPrice = buf.readLong();
            String formattedPrice = buf.readUtf();
            int bulkSize = buf.readVarInt();
            int maxStack = buf.readVarInt();
            boolean finite = buf.readBoolean();
            int stockRemaining = buf.readVarInt();
            boolean enabled = buf.readBoolean();
            List<String> categories = buf.readList(FriendlyByteBuf::readUtf);
            int sort = buf.readVarInt();
            return new ProductDto(id, itemId, itemExpression.isEmpty() ? null : itemExpression, displayName,
                    description, currency, unitPrice, formattedPrice, bulkSize, maxStack,
                    finite ? StockMode.FINITE : StockMode.INFINITE, stockRemaining, enabled, categories, sort);
        }
    }

    // ---------------------------------------------------------------- payloads

    /** 服务端 → 客户端：打开商店界面。 */
    public record OpenShopPayload() implements CustomPacketPayload {
        public static final Type<OpenShopPayload> TYPE = new Type<>(id("open_shop"));
        public static final StreamCodec<FriendlyByteBuf, OpenShopPayload> STREAM_CODEC =
                StreamCodec.ofMember((buf, payload) -> {
                }, buf -> new OpenShopPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：全量商店状态（分类 + 商品 + 余额）。 */
    public record SyncShopPayload(
            List<CategoryDto> categories,
            List<ProductDto> products,
            Map<String, String> balances,
            Map<String, Long> balanceAmounts,
            Map<String, String> currencyNames,
            String defaultCurrency,
            boolean hideEmptyCategories
    ) implements CustomPacketPayload {
        public static final Type<SyncShopPayload> TYPE = new Type<>(id("sync_shop"));
        public static final StreamCodec<FriendlyByteBuf, SyncShopPayload> STREAM_CODEC =
                StreamCodec.ofMember(SyncShopPayload::write, SyncShopPayload::read);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeCollection(categories, (buffer, dto) -> dto.write(buffer));
            buf.writeCollection(products, (buffer, dto) -> dto.write(buffer));
            writeStringMap(buf, balances);
            buf.writeVarInt(balanceAmounts.size());
            balanceAmounts.forEach((key, value) -> {
                buf.writeUtf(key);
                buf.writeLong(value);
            });
            writeStringMap(buf, currencyNames);
            buf.writeUtf(defaultCurrency == null ? "" : defaultCurrency);
            buf.writeBoolean(hideEmptyCategories);
        }

        static SyncShopPayload read(FriendlyByteBuf buf) {
            List<CategoryDto> categories = buf.readList(CategoryDto::read);
            List<ProductDto> products = buf.readList(ProductDto::read);
            Map<String, String> balances = readStringMap(buf);
            int amountSize = buf.readVarInt();
            Map<String, Long> balanceAmounts = new LinkedHashMap<>();
            for (int i = 0; i < amountSize; i++) {
                balanceAmounts.put(buf.readUtf(), buf.readLong());
            }
            Map<String, String> currencyNames = readStringMap(buf);
            String defaultCurrency = buf.readUtf();
            boolean hideEmptyCategories = buf.readBoolean();
            return new SyncShopPayload(categories, products, balances, balanceAmounts, currencyNames,
                    defaultCurrency, hideEmptyCategories);
        }
    }

    /** 客户端 → 服务端：请求重新同步（打开界面 / 点击刷新）。 */
    public record RequestSyncPayload() implements CustomPacketPayload {
        public static final Type<RequestSyncPayload> TYPE = new Type<>(id("request_sync"));
        public static final StreamCodec<FriendlyByteBuf, RequestSyncPayload> STREAM_CODEC =
                StreamCodec.ofMember((buf, payload) -> {
                }, buf -> new RequestSyncPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：购买请求。 */
    public record PurchaseRequestPayload(
            String productId,
            byte mode,
            int requestedQuantity,
            String requestId
    ) implements CustomPacketPayload {
        public static final Type<PurchaseRequestPayload> TYPE = new Type<>(id("purchase_request"));
        public static final StreamCodec<FriendlyByteBuf, PurchaseRequestPayload> STREAM_CODEC =
                StreamCodec.ofMember(PurchaseRequestPayload::write, PurchaseRequestPayload::read);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(productId);
            buf.writeByte(mode);
            buf.writeVarInt(requestedQuantity);
            buf.writeUtf(requestId);
        }

        private static PurchaseRequestPayload read(FriendlyByteBuf buf) {
            return new PurchaseRequestPayload(buf.readUtf(), buf.readByte(), buf.readVarInt(), buf.readUtf());
        }
    }

    /** 服务端 → 客户端：购买结果（含余额/库存更新）。 */
    public record PurchaseResultPayload(
            String requestId,
            boolean success,
            String messageKey,
            int quantity,
            long totalPrice,
            Map<String, String> balances,
            Map<String, Integer> stockUpdates
    ) implements CustomPacketPayload {
        public static final Type<PurchaseResultPayload> TYPE = new Type<>(id("purchase_result"));
        public static final StreamCodec<FriendlyByteBuf, PurchaseResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(PurchaseResultPayload::write, PurchaseResultPayload::read);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(requestId);
            buf.writeBoolean(success);
            buf.writeUtf(messageKey == null ? "" : messageKey);
            buf.writeVarInt(quantity);
            buf.writeLong(totalPrice);
            buf.writeVarInt(balances.size());
            balances.forEach((key, value) -> {
                buf.writeUtf(key);
                buf.writeUtf(value == null ? "" : value);
            });
            buf.writeVarInt(stockUpdates.size());
            stockUpdates.forEach((key, value) -> {
                buf.writeUtf(key);
                buf.writeVarInt(value);
            });
        }

        private static PurchaseResultPayload read(FriendlyByteBuf buf) {
            String requestId = buf.readUtf();
            boolean success = buf.readBoolean();
            String messageKey = buf.readUtf();
            int quantity = buf.readVarInt();
            long totalPrice = buf.readLong();
            int balanceSize = buf.readVarInt();
            Map<String, String> balances = new LinkedHashMap<>();
            for (int i = 0; i < balanceSize; i++) {
                balances.put(buf.readUtf(), buf.readUtf());
            }
            int stockSize = buf.readVarInt();
            Map<String, Integer> stockUpdates = new LinkedHashMap<>();
            for (int i = 0; i < stockSize; i++) {
                stockUpdates.put(buf.readUtf(), buf.readVarInt());
            }
            return new PurchaseResultPayload(requestId, success, messageKey.isEmpty() ? null : messageKey,
                    quantity, totalPrice, balances, stockUpdates);
        }
    }

    /** 便捷构造（避免反复写 lambda 泛型）。 */
    public static SyncShopPayload syncOf(List<CategoryDto> categories, List<ProductDto> products,
                                         Map<String, String> balances, Map<String, Long> balanceAmounts,
                                         Map<String, String> currencyNames, String defaultCurrency,
                                         boolean hideEmptyCategories) {
        return new SyncShopPayload(new ArrayList<>(categories), new ArrayList<>(products), balances,
                balanceAmounts, currencyNames, defaultCurrency, hideEmptyCategories);
    }

    private static void writeStringMap(FriendlyByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        map.forEach((key, value) -> {
            buf.writeUtf(key);
            buf.writeUtf(value == null ? "" : value);
        });
    }

    private static Map<String, String> readStringMap(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            result.put(buf.readUtf(), buf.readUtf());
        }
        return result;
    }
}
