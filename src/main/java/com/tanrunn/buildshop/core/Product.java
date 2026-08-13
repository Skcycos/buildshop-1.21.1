package com.tanrunn.buildshop.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 商品定义（纯数据，无 Minecraft 依赖）。
 *
 * <p>价格按单个物品计算（整数最小单位）。{@code components} 保留原始 JSON 以便
 * 服务端构造真实 ItemStack。</p>
 *
 * <p>商品 ID 优先取 JSON 的 {@code id} 字段（稳定、跨 namespace 可区分）；
 * 缺省时回退为数据包资源键 {@code namespace:path}。解析对错误字段健壮：
 * 类型错误/非法数值一律记录到 errors 并跳过该商品，不中断整个目录加载。</p>
 */
public final class Product {
    public static final String CURRENCY_DEFAULT = "virtual_coins";

    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private final String id;
    private final String itemId;
    private final JsonElement components;
    private final List<String> categories;
    private final String currency;
    private final long unitPrice;
    private final int bulkSize;
    private final int maxStack;
    private final StockMode stockMode;
    private final int stockQuantity;
    private final String displayName;
    private final String description;
    private final boolean enabled;
    private final int sort;

    private Product(Builder builder) {
        this.id = builder.id;
        this.itemId = builder.itemId;
        this.components = builder.components;
        this.categories = List.copyOf(builder.categories);
        this.currency = builder.currency == null || builder.currency.isBlank() ? CURRENCY_DEFAULT : builder.currency;
        this.unitPrice = builder.unitPrice;
        this.bulkSize = Math.max(0, builder.bulkSize);
        this.maxStack = Math.max(1, builder.maxStack);
        this.stockMode = builder.stockMode;
        this.stockQuantity = Math.max(0, builder.stockQuantity);
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.enabled = builder.enabled;
        this.sort = builder.sort;
    }

    /** 资源 ID 语法校验（无 Minecraft 依赖的轻量检查；服务端还会对照注册表）。 */
    public static boolean isValidItemId(String itemId) {
        return itemId != null && ITEM_ID_PATTERN.matcher(itemId).matches();
    }

    /**
     * 从数据包 JSON 解析商品。返回 null 表示解析失败（记录原因到 errors）。
     *
     * @param resourceKey 数据包资源键（namespace:path），用于日志定位；仅当 JSON 缺少 id 时作为商品 ID
     */
    public static Product fromJson(String resourceKey, JsonObject json, List<String> errors) {
        String prefix = "product '" + resourceKey + "': ";

        String id = optString(json, "id", null);
        if (id == null || id.isBlank()) {
            id = resourceKey;
        }

        String itemId = optString(json, "item", "");
        if (itemId.isBlank()) {
            errors.add(prefix + "missing or empty 'item'");
            return null;
        }
        if (!isValidItemId(itemId)) {
            errors.add(prefix + "invalid 'item' id '" + itemId + "'");
            return null;
        }

        if (!json.has("unitPrice") || !json.get("unitPrice").isJsonPrimitive()) {
            errors.add(prefix + "missing 'unitPrice'");
            return null;
        }
        long unitPrice;
        try {
            unitPrice = json.get("unitPrice").getAsLong();
        } catch (NumberFormatException e) {
            errors.add(prefix + "'unitPrice' is not a valid number: " + json.get("unitPrice"));
            return null;
        }
        if (unitPrice <= 0) {
            errors.add(prefix + "'unitPrice' must be positive");
            return null;
        }

        int bulkSize = optInt(json, "bulkSize", 0);
        if (bulkSize < 0) {
            errors.add(prefix + "'bulkSize' must be >= 0");
            return null;
        }

        List<String> categories = new ArrayList<>();
        JsonElement categoryElement = json.get("categories");
        if (categoryElement != null && categoryElement.isJsonArray()) {
            for (JsonElement element : categoryElement.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    String category = element.getAsString();
                    if (!category.isBlank()) categories.add(category);
                }
            }
        }

        StockMode stockMode = StockMode.INFINITE;
        int stockQuantity = 0;
        JsonElement stockElement = json.get("stock");
        if (stockElement != null && stockElement.isJsonObject()) {
            JsonObject stock = stockElement.getAsJsonObject();
            String mode = optString(stock, "mode", "infinite");
            if ("finite".equalsIgnoreCase(mode)) {
                if (!stock.has("quantity") || !stock.get("quantity").isJsonPrimitive()) {
                    errors.add(prefix + "finite stock requires a numeric 'quantity'");
                    return null;
                }
                int quantity;
                try {
                    quantity = stock.get("quantity").getAsInt();
                } catch (NumberFormatException e) {
                    errors.add(prefix + "'stock.quantity' is not a valid integer: " + stock.get("quantity"));
                    return null;
                }
                if (quantity < 0) {
                    errors.add(prefix + "'stock.quantity' must be >= 0");
                    return null;
                }
                stockMode = StockMode.FINITE;
                stockQuantity = quantity;
            }
        }

        // enabled 必须是布尔 primitive；对象/数组等非法值按"未配置（启用）"处理，
        // 记录警告但绝不终止目录加载。
        boolean enabled;
        if (!json.has("enabled")) {
            enabled = true;
        } else if (json.get("enabled").isJsonPrimitive()) {
            enabled = json.get("enabled").getAsBoolean();
        } else {
            errors.add(prefix + "'enabled' must be a boolean, treating as enabled");
            enabled = true;
        }

        return new Builder(id, itemId)
                .components(json.get("components"))
                .categories(categories)
                .currency(optString(json, "currency", CURRENCY_DEFAULT))
                .unitPrice(unitPrice)
                .bulkSize(bulkSize)
                .stockMode(stockMode)
                .stockQuantity(stockQuantity)
                .displayName(optString(json, "displayName", null))
                .description(optString(json, "description", null))
                .enabled(enabled)
                .sort(optInt(json, "sort", 0))
                .build();
    }

    public String id() {
        return id;
    }

    public String itemId() {
        return itemId;
    }

    /** 商品组件的原始 JSON（可为 null）。 */
    public JsonElement components() {
        return components;
    }

    /** 组件序列化为 SNBT 的文本（如 {@code {"minecraft:custom_name":'...'}}），无组件时为 null。 */
    public String componentsSnbt() {
        return components == null ? null : components.toString();
    }

    public List<String> categories() {
        return categories;
    }

    public boolean inCategory(String categoryId) {
        return Category.ALL_ID.equals(categoryId) || categories.contains(categoryId);
    }

    public String currency() {
        return currency;
    }

    public long unitPrice() {
        return unitPrice;
    }

    /** Shift+左键购买数量；0 表示未配置（使用物品最大堆叠数量）。 */
    public int bulkSize() {
        return bulkSize;
    }

    /** 物品最大堆叠数量（服务端从注册表读取后填充，默认 64）。 */
    public int maxStack() {
        return maxStack;
    }

    public Product withMaxStack(int maxStack) {
        if (maxStack == this.maxStack) return this;
        return new Builder(id, itemId)
                .components(components)
                .categories(categories)
                .currency(currency)
                .unitPrice(unitPrice)
                .bulkSize(bulkSize)
                .maxStack(maxStack)
                .stockMode(stockMode)
                .stockQuantity(stockQuantity)
                .displayName(displayName)
                .description(description)
                .enabled(enabled)
                .sort(sort)
                .build();
    }

    /** Shift 购买数量：优先 bulkSize，否则物品最大堆叠。 */
    public int effectiveBulkSize() {
        return bulkSize > 0 ? bulkSize : maxStack;
    }

    public StockMode stockMode() {
        return stockMode;
    }

    public int stockQuantity() {
        return stockQuantity;
    }

    public String displayName() {
        return displayName;
    }

    /** 显示名：未配置时回退为物品 ID。 */
    public String effectiveName() {
        return displayName == null || displayName.isBlank() ? itemId : displayName;
    }

    public String description() {
        return description;
    }

    public boolean enabled() {
        return enabled;
    }

    public int sort() {
        return sort;
    }

    @Override
    public String toString() {
        return "Product{" + id + " -> " + itemId + ", price=" + unitPrice + "}";
    }

    private static String optString(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static final class Builder {
        private final String id;
        private final String itemId;
        private JsonElement components;
        private List<String> categories = Collections.emptyList();
        private String currency = CURRENCY_DEFAULT;
        private long unitPrice;
        private int bulkSize;
        private int maxStack = 64;
        private StockMode stockMode = StockMode.INFINITE;
        private int stockQuantity;
        private String displayName;
        private String description;
        private boolean enabled = true;
        private int sort;

        public Builder(String id, String itemId) {
            this.id = Objects.requireNonNull(id, "id");
            this.itemId = Objects.requireNonNull(itemId, "itemId");
        }

        public Builder components(JsonElement components) {
            this.components = components;
            return this;
        }

        public Builder categories(List<String> categories) {
            this.categories = categories;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder unitPrice(long unitPrice) {
            this.unitPrice = unitPrice;
            return this;
        }

        public Builder bulkSize(int bulkSize) {
            this.bulkSize = bulkSize;
            return this;
        }

        public Builder maxStack(int maxStack) {
            this.maxStack = maxStack;
            return this;
        }

        public Builder stockMode(StockMode stockMode) {
            this.stockMode = stockMode;
            return this;
        }

        public Builder stockQuantity(int stockQuantity) {
            this.stockQuantity = stockQuantity;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder sort(int sort) {
            this.sort = sort;
            return this;
        }

        public Product build() {
            return new Product(this);
        }
    }
}
