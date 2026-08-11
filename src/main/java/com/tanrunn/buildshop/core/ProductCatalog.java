package com.tanrunn.buildshop.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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
     * @param rawCategories {@code ResourceLocation.path -> JsonElement}
     * @param rawProducts   {@code ResourceLocation.path -> JsonElement}
     */
    public static ProductCatalog fromJson(Map<String, JsonElement> rawCategories, Map<String, JsonElement> rawProducts) {
        List<String> errors = new ArrayList<>();
        List<Category> categories = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : rawCategories.entrySet()) {
            String id = entry.getKey();
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) {
                errors.add("category '" + id + "': not a JSON object");
                continue;
            }
            categories.add(Category.fromJson(id, element.getAsJsonObject()));
        }
        categories.sort(Comparator.comparingInt(Category::sort).thenComparing(Category::id));

        List<Product> products = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : rawProducts.entrySet()) {
            String id = entry.getKey();
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) {
                errors.add("product '" + id + "': not a JSON object");
                continue;
            }
            Product product = Product.fromJson(id, element.getAsJsonObject(), errors);
            if (product != null) products.add(product);
        }
        products.sort(Comparator.comparingInt(Product::sort).thenComparing(Product::id));

        if (!errors.isEmpty()) {
            LOGGER.warn("ProductCatalog: {} invalid entries: {}", errors.size(), errors);
        }
        return new ProductCatalog(categories, products);
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
}
