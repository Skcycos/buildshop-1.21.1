package com.tanrunn.buildshop.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.core.ProductCatalog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据包加载器：
 * <pre>
 * data/&lt;namespace&gt;/building_shop/categories/&lt;id&gt;.json
 * data/&lt;namespace&gt;/building_shop/products/&lt;id&gt;.json
 * </pre>
 *
 * <p>商品 ID 与分类 ID 取文件名（不含 .json），排序由 JSON 的 {@code sort} 字段决定。</p>
 */
public final class ShopDataLoader {

    private static final Gson GSON = new Gson();

    private final Map<String, JsonElement> rawCategories = new LinkedHashMap<>();
    private final Map<String, JsonElement> rawProducts = new LinkedHashMap<>();
    private boolean loadedOnce;

    public SimpleJsonResourceReloadListener categoriesListener() {
        return new SimpleJsonResourceReloadListener(GSON, ProductCatalog.CATEGORY_FOLDER) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
                rawCategories.clear();
                for (Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
                    rawCategories.put(fileName(entry.getKey()), entry.getValue());
                }
                BuildShopMod.LOGGER.info("Shop categories loaded: {}", rawCategories.size());
                rebuildIfReady();
            }
        };
    }

    public SimpleJsonResourceReloadListener productsListener() {
        return new SimpleJsonResourceReloadListener(GSON, ProductCatalog.PRODUCT_FOLDER) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
                rawProducts.clear();
                for (Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
                    rawProducts.put(fileName(entry.getKey()), entry.getValue());
                }
                BuildShopMod.LOGGER.info("Shop products loaded: {}", rawProducts.size());
                rebuildIfReady();
            }
        };
    }

    private void rebuildIfReady() {
        loadedOnce = true;
        ProductCatalog catalog = ProductCatalog.fromJson(rawCategories, rawProducts);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            ShopServer.INSTANCE.applyCatalogHeadless(catalog);
            return;
        }
        ShopServer.INSTANCE.onCatalogReload(catalog, server.overworld());
    }

    public boolean isLoaded() {
        return loadedOnce;
    }

    private static String fileName(ResourceLocation location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
