package com.tanrunn.buildshop.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.core.ProductCatalog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
 * <p>商品/分类 ID 优先取 JSON 的 {@code id} 字段；内部资源键使用完整的
 * {@code namespace:path}（不同 namespace 的同名文件不会互相覆盖）。
 * 排序由 JSON 的 {@code sort} 字段决定。</p>
 *
 * <p>原子性：分类与商品是两个独立 reload listener，各自 apply 时会得到半份数据。
 * 本类按"同一 reload 周期（同一 ResourceManager 实例）内两个 listener 都完成"才
 * 重建并应用完整目录，避免中间半成品目录或重复初始化库存。</p>
 *
 * <p>内置示例数据包（本 mod 打包的 {@code data/buildshop} 内容）：当
 * {@link Config#LOAD_BUILTIN_DATAPACK} 关闭时被忽略，商店只展示服务端
 * 数据包（其它命名空间）提供的内容。</p>
 */
public final class ShopDataLoader {

    private static final Gson GSON = new Gson();

    private final Map<String, JsonElement> rawCategories = new LinkedHashMap<>();
    private final Map<String, JsonElement> rawProducts = new LinkedHashMap<>();
    private boolean loadedOnce;

    /** 当前 reload 周期（按 ResourceManager 实例身份区分）。 */
    private Object epochManager;
    private boolean categoriesAppliedInEpoch;
    private boolean productsAppliedInEpoch;

    public SimpleJsonResourceReloadListener categoriesListener() {
        return new SimpleJsonResourceReloadListener(GSON, ProductCatalog.CATEGORY_FOLDER) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
                beginEpoch(manager);
                rawCategories.clear();
                for (Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
                    if (acceptEntry(Config.LOAD_BUILTIN_DATAPACK.get(), entry.getKey())) {
                        rawCategories.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                categoriesAppliedInEpoch = true;
                BuildShopMod.LOGGER.info("Shop categories loaded: {}", rawCategories.size());
                rebuildIfReady();
            }
        };
    }

    public SimpleJsonResourceReloadListener productsListener() {
        return new SimpleJsonResourceReloadListener(GSON, ProductCatalog.PRODUCT_FOLDER) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
                beginEpoch(manager);
                rawProducts.clear();
                for (Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
                    if (acceptEntry(Config.LOAD_BUILTIN_DATAPACK.get(), entry.getKey())) {
                        rawProducts.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                productsAppliedInEpoch = true;
                BuildShopMod.LOGGER.info("Shop products loaded: {}", rawProducts.size());
                rebuildIfReady();
            }
        };
    }

    /**
     * 是否接受该资源（纯逻辑，可单测）：配置开启内置示例数据包时全量接受；
     * 配置关闭时忽略本 mod 命名空间（内置示例包所在）的内容，服务端自备
     * 数据包（其它命名空间）不受影响。
     *
     * @param loadBuiltin {@link Config#LOAD_BUILTIN_DATAPACK} 的值（由调用方读取）
     * @param key 资源键
     */
    static boolean acceptEntry(boolean loadBuiltin, ResourceLocation key) {
        if (key == null) {
            return false;
        }
        return loadBuiltin || !BuildShopMod.MODID.equals(key.getNamespace());
    }

    private void beginEpoch(ResourceManager manager) {
        if (manager != epochManager) {
            epochManager = manager;
            categoriesAppliedInEpoch = false;
            productsAppliedInEpoch = false;
        }
    }

    private void rebuildIfReady() {
        loadedOnce = true;
        if (!categoriesAppliedInEpoch || !productsAppliedInEpoch) {
            return;
        }
        ProductCatalog catalog = ProductCatalog.fromJson(rawCategories, rawProducts);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel overworld = server == null ? null : server.overworld();
        if (overworld == null) {
            ShopServer.INSTANCE.applyCatalogHeadless(catalog);
            return;
        }
        ShopServer.INSTANCE.applyCatalog(catalog, overworld);
    }

    public boolean isLoaded() {
        return loadedOnce;
    }
}
