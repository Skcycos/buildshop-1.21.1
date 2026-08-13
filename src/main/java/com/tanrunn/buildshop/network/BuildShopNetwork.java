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

    /** 客户端提交的字符串最大长度（防御超大字符串占用服务器内存）。 */
    private static final int MAX_STRING_LENGTH = 256;
    private static final int MAX_PRODUCT_ID_LENGTH = 128;
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private BuildShopNetwork() {
        throw new AssertionError();
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(OpenShopPayload.TYPE, OpenShopPayload.STREAM_CODEC, ClientHandler::handleOpenShop);
        registrar.playToClient(SyncShopPayload.TYPE, SyncShopPayload.STREAM_CODEC, ClientHandler::handleSyncShop);
        registrar.playToClient(ShopDisabledPayload.TYPE, ShopDisabledPayload.STREAM_CODEC, ClientHandler::handleShopDisabled);
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
            buf.writeUtf(id, MAX_STRING_LENGTH);
            buf.writeUtf(name == null ? "" : name, MAX_STRING_LENGTH);
            buf.writeUtf(iconExpression == null ? "" : iconExpression, MAX_STRING_LENGTH);
            buf.writeVarInt(sort);
        }

        static CategoryDto read(FriendlyByteBuf buf) {
            String id = buf.readUtf(MAX_STRING_LENGTH);
            String name = buf.readUtf(MAX_STRING_LENGTH);
            String icon = buf.readUtf(MAX_STRING_LENGTH);
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

        public ProductDto withStockRemaining(int remaining) {
            return new ProductDto(id, itemId, itemExpression, displayName, description, currency, unitPrice,
                    formattedPrice, bulkSize, maxStack, stockMode, remaining, enabled, categories, sort);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(id, MAX_STRING_LENGTH);
            buf.writeUtf(itemId == null ? "" : itemId, MAX_STRING_LENGTH);
            buf.writeUtf(itemExpression == null ? "" : itemExpression, MAX_STRING_LENGTH * 4);
            buf.writeUtf(displayName == null ? "" : displayName, MAX_STRING_LENGTH);
            buf.writeUtf(description == null ? "" : description, MAX_STRING_LENGTH * 4);
            buf.writeUtf(currency == null ? "" : currency, MAX_STRING_LENGTH);
            buf.writeLong(unitPrice);
            buf.writeUtf(formattedPrice == null ? "" : formattedPrice, MAX_STRING_LENGTH);
            buf.writeVarInt(bulkSize);
            buf.writeVarInt(maxStack);
            buf.writeBoolean(stockMode == StockMode.FINITE);
            buf.writeVarInt(stockRemaining);
            buf.writeBoolean(enabled);
            buf.writeCollection(categories, (buffer, value) -> buffer.writeUtf(value, MAX_STRING_LENGTH));
            buf.writeVarInt(sort);
        }

        static ProductDto read(FriendlyByteBuf buf) {
            String id = buf.readUtf(MAX_STRING_LENGTH);
            String itemId = buf.readUtf(MAX_STRING_LENGTH);
            String itemExpression = buf.readUtf(MAX_STRING_LENGTH * 4);
            String displayName = buf.readUtf(MAX_STRING_LENGTH);
            String description = buf.readUtf(MAX_STRING_LENGTH * 4);
            String currency = buf.readUtf(MAX_STRING_LENGTH);
            long unitPrice = buf.readLong();
            String formattedPrice = buf.readUtf(MAX_STRING_LENGTH);
            int bulkSize = buf.readVarInt();
            int maxStack = buf.readVarInt();
            boolean finite = buf.readBoolean();
            int stockRemaining = buf.readVarInt();
            boolean enabled = buf.readBoolean();
            List<String> categories = buf.readList(buffer -> buffer.readUtf(MAX_STRING_LENGTH));
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
                buf.writeUtf(key, MAX_STRING_LENGTH);
                buf.writeLong(value);
            });
            writeStringMap(buf, currencyNames);
            buf.writeUtf(defaultCurrency == null ? "" : defaultCurrency, MAX_STRING_LENGTH);
            buf.writeBoolean(hideEmptyCategories);
        }

        static SyncShopPayload read(FriendlyByteBuf buf) {
            List<CategoryDto> categories = buf.readList(CategoryDto::read);
            List<ProductDto> products = buf.readList(ProductDto::read);
            Map<String, String> balances = readStringMap(buf);
            int amountSize = buf.readVarInt();
            Map<String, Long> balanceAmounts = new LinkedHashMap<>();
            for (int i = 0; i < amountSize; i++) {
                balanceAmounts.put(buf.readUtf(MAX_STRING_LENGTH), buf.readLong());
            }
            Map<String, String> currencyNames = readStringMap(buf);
            String defaultCurrency = buf.readUtf(MAX_STRING_LENGTH);
            boolean hideEmptyCategories = buf.readBoolean();
            return new SyncShopPayload(categories, products, balances, balanceAmounts, currencyNames,
                    defaultCurrency, hideEmptyCategories);
        }
    }

    /** 服务端 → 客户端：商店总开关已关闭（打开/请求同步时的自然提示）。 */
    public record ShopDisabledPayload() implements CustomPacketPayload {
        public static final Type<ShopDisabledPayload> TYPE = new Type<>(id("shop_disabled"));
        public static final StreamCodec<FriendlyByteBuf, ShopDisabledPayload> STREAM_CODEC =
                StreamCodec.ofMember((buf, payload) -> {
                }, buf -> new ShopDisabledPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
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

    /** 客户端 → 服务端：购买请求。字段全部有长度上限，拒绝超长字符串。 */
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
            buf.writeUtf(productId, MAX_PRODUCT_ID_LENGTH);
            buf.writeByte(mode);
            buf.writeVarInt(requestedQuantity);
            buf.writeUtf(requestId, MAX_REQUEST_ID_LENGTH);
        }

        private static PurchaseRequestPayload read(FriendlyByteBuf buf) {
            return new PurchaseRequestPayload(buf.readUtf(MAX_PRODUCT_ID_LENGTH), buf.readByte(),
                    buf.readVarInt(), buf.readUtf(MAX_REQUEST_ID_LENGTH));
        }
    }

    /** 服务端 → 客户端：购买结果（含余额文本/数值、库存更新）。 */
    public record PurchaseResultPayload(
            String requestId,
            boolean success,
            String messageKey,
            int quantity,
            long totalPrice,
            Map<String, String> balances,
            Map<String, Long> balanceAmounts,
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
            buf.writeUtf(requestId, MAX_REQUEST_ID_LENGTH);
            buf.writeBoolean(success);
            buf.writeUtf(messageKey == null ? "" : messageKey, MAX_STRING_LENGTH);
            buf.writeVarInt(quantity);
            buf.writeLong(totalPrice);
            buf.writeVarInt(balances.size());
            balances.forEach((key, value) -> {
                buf.writeUtf(key, MAX_STRING_LENGTH);
                buf.writeUtf(value == null ? "" : value, MAX_STRING_LENGTH);
            });
            buf.writeVarInt(balanceAmounts.size());
            balanceAmounts.forEach((key, value) -> {
                buf.writeUtf(key, MAX_STRING_LENGTH);
                buf.writeLong(value);
            });
            buf.writeVarInt(stockUpdates.size());
            stockUpdates.forEach((key, value) -> {
                buf.writeUtf(key, MAX_STRING_LENGTH);
                buf.writeVarInt(value);
            });
        }

        private static PurchaseResultPayload read(FriendlyByteBuf buf) {
            String requestId = buf.readUtf(MAX_REQUEST_ID_LENGTH);
            boolean success = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_STRING_LENGTH);
            int quantity = buf.readVarInt();
            long totalPrice = buf.readLong();
            int balanceSize = buf.readVarInt();
            Map<String, String> balances = new LinkedHashMap<>();
            for (int i = 0; i < balanceSize; i++) {
                balances.put(buf.readUtf(MAX_STRING_LENGTH), buf.readUtf(MAX_STRING_LENGTH));
            }
            int amountSize = buf.readVarInt();
            Map<String, Long> balanceAmounts = new LinkedHashMap<>();
            for (int i = 0; i < amountSize; i++) {
                balanceAmounts.put(buf.readUtf(MAX_STRING_LENGTH), buf.readLong());
            }
            int stockSize = buf.readVarInt();
            Map<String, Integer> stockUpdates = new LinkedHashMap<>();
            for (int i = 0; i < stockSize; i++) {
                stockUpdates.put(buf.readUtf(MAX_STRING_LENGTH), buf.readVarInt());
            }
            return new PurchaseResultPayload(requestId, success, messageKey.isEmpty() ? null : messageKey,
                    quantity, totalPrice, balances, balanceAmounts, stockUpdates);
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
            buf.writeUtf(key, MAX_STRING_LENGTH);
            buf.writeUtf(value == null ? "" : value, MAX_STRING_LENGTH);
        });
    }

    private static Map<String, String> readStringMap(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            result.put(buf.readUtf(MAX_STRING_LENGTH), buf.readUtf(MAX_STRING_LENGTH));
        }
        return result;
    }
}
