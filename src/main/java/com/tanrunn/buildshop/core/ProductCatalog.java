package com.tanrunn.buildshop.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 商品与分类目录（纯数据）。
 *
 * <p>由数据包 JSON 构建，支持原子替换（{@link CatalogStore}）。排序只依赖
 * {@code sort} 字段，不依赖文件名。</p>
 *
 * <p>资源键为完整的 {@code namespace:path}（不同 namespace 的同名文件不会冲突）；
 * 商品/分类 ID 取 JSON {@code id} 字段，缺省回退为资源键。重复 ID 保留第一个并记录错误；
 * 商品引用了不存在的分类只记警告，不中断加载。</p>
 */
public final class ProductCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger("buildshop.core");

    /** 数据包文件夹（相对 data/&lt;namespace&gt;/ 的路径）。 */
    public static final String CATEGORY_FOLDER = "building_shop/categories";
    public static final String PRODUCT_FOLDER = "building_shop/products";

    private final List<Category> categories;
    private final List<Product> products;
    private final Map<String, Category> categoryById;
    private final Map<String, Product> productById;

    private ProductCatalog(List<Category> categories, List<Product> products) {
        this.categories = List.copyOf(categories);
        this.products = List.copyOf(products);
        Map<String, Category> categoryMap = new LinkedHashMap<>();
        for (Category category : categories) {
            categoryMap.put(category.id(), category);
        }
        this.categoryById = Map.copyOf(categoryMap);
        Map<String, Product> productMap = new LinkedHashMap<>();
        for (Product product : products) {
            productMap.put(product.id(), product);
        }
        this.productById = Map.copyOf(productMap);
    }

    /** 空目录。 */
    public static ProductCatalog empty() {
        return new ProductCatalog(List.of(), List.of());
    }

    /**
     * 从数据包 JSON 构建目录。
     *
     * @param rawCategories {@code namespace:path -> JsonElement}
     * @param rawProducts   {@code namespace:path -> JsonElement}
     */
    public static ProductCatalog fromJson(Map<String, JsonElement> rawCategories, Map<String, JsonElement> rawProducts) {
        List<String> errors = new ArrayList<>();

        List<Category> categories = new ArrayList<>();
        Map<String, String> categorySourceByKey = new HashMap<>();
        List<Map.Entry<String, JsonElement>> sortedCategories = new ArrayList<>(rawCategories.entrySet());
        sortedCategories.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, JsonElement> entry : sortedCategories) {
            String key = entry.getKey();
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) {
                errors.add("category '" + key + "': not a JSON object");
                continue;
            }
            try {
                Category category = Category.fromJson(key, element.getAsJsonObject());
                String conflict = categorySourceByKey.putIfAbsent(category.id(), key);
                if (conflict != null) {
                    errors.add("category id '" + category.id() + "' duplicated by '" + conflict + "' and '" + key
                            + "'; keeping '" + conflict + "'");
                    continue;
                }
                categories.add(category);
            } catch (RuntimeException e) {
                errors.add("category '" + key + "': unparseable: " + e.getMessage());
            }
        }
        categories.sort(Comparator.comparingInt(Category::sort).thenComparing(Category::id));

        List<Product> products = new ArrayList<>();
        Map<String, String> productSourceByKey = new HashMap<>();
        List<Map.Entry<String, JsonElement>> sortedProducts = new ArrayList<>(rawProducts.entrySet());
        sortedProducts.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, JsonElement> entry : sortedProducts) {
            String key = entry.getKey();
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) {
                errors.add("product '" + key + "': not a JSON object");
                continue;
            }
            Product product;
            try {
                product = Product.fromJson(key, element.getAsJsonObject(), errors);
            } catch (RuntimeException e) {
                errors.add("product '" + key + "': unparseable: " + e.getMessage());
                continue;
            }
            if (product == null) continue;
            String conflict = productSourceByKey.putIfAbsent(product.id(), key);
            if (conflict != null) {
                errors.add("product id '" + product.id() + "' duplicated by '" + conflict + "' and '" + key
                        + "'; keeping '" + conflict + "'");
                continue;
            }
            products.add(product);
        }
        products.sort(Comparator.comparingInt(Product::sort).thenComparing(Product::id));

        // 分类引用校验：未知分类只警告，不影响目录构建。
        for (Product product : products) {
            for (String categoryId : product.categories()) {
                if (!categoryMapContains(categories, categoryId)) {
                    LOGGER.warn("ProductCatalog: product '{}' references unknown category '{}'",
                            product.id(), categoryId);
                }
            }
        }

        if (!errors.isEmpty()) {
            LOGGER.warn("ProductCatalog: {} invalid entries: {}", errors.size(), errors);
        }
        return new ProductCatalog(categories, products);
    }

    private static boolean categoryMapContains(List<Category> categories, String id) {
        for (Category category : categories) {
            if (category.id().equals(id)) return true;
        }
        return false;
    }

    /** 重新应用所有商品的最大堆叠数量（服务端从物品注册表读取）。 */
    public ProductCatalog withMaxStacks(java.util.function.Function<String, Integer> maxStackResolver) {
        List<Product> updated = new ArrayList<>(products.size());
        boolean changed = false;
        for (Product product : products) {
            int maxStack = maxStackResolver.apply(product.itemId());
            int resolved = maxStack > 0 ? maxStack : 64;
            if (resolved != product.maxStack()) {
                updated.add(product.withMaxStack(resolved));
                changed = true;
            } else {
                updated.add(product);
            }
        }
        return changed ? new ProductCatalog(categories, updated) : this;
    }

    public List<Category> categories() {
        return categories;
    }

    public List<Product> products() {
        return products;
    }

    /** 启用中的商品（按 sort 排序）。 */
    public List<Product> enabledProducts() {
        return products.stream().filter(Product::enabled).toList();
    }

    public Optional<Category> category(String id) {
        return Optional.ofNullable(categoryById.get(id));
    }

    public Optional<Product> product(String id) {
        return Optional.ofNullable(productById.get(id));
    }

    public int productCount() {
        return products.size();
    }

    public int categoryCount() {
        return categories.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductCatalog that)) return false;
        return products.equals(that.products) && categories.equals(that.categories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(products, categories);
    }
}
