package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.api.PaymentResult;
import com.tanrunn.buildshop.api.ShopCurrencyProvider;
import com.tanrunn.buildshop.core.CatalogStore;
import com.tanrunn.buildshop.core.FitCalculator;
import com.tanrunn.buildshop.core.IdempotencyStore;
import com.tanrunn.buildshop.core.ItemDispatcher;
import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.ProductCatalog;
import com.tanrunn.buildshop.core.PurchaseEngine;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.core.PurchaseRequest;
import com.tanrunn.buildshop.core.PurchaseResult;
import com.tanrunn.buildshop.core.StockMode;
import com.tanrunn.buildshop.core.StockStore;
import com.tanrunn.buildshop.core.Wallet;
import com.tanrunn.buildshop.network.BuildShopNetwork;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 商店服务端门面：目录/库存/幂等/购买事务/状态同步。
 *
 * <p>所有方法必须在服务端主线程调用。</p>
 */
public final class ShopServer {

    public static final ShopServer INSTANCE = new ShopServer();

    private final CatalogStore catalogStore = new CatalogStore();
    private final StockStore stockStore = new StockStore();
    private final Map<UUID, PlayerIdempotency> idempotencyByPlayer = new LinkedHashMap<>();
    private final Map<UUID, Long> lastRequestTick = new LinkedHashMap<>();

    private ShopServer() {
    }

    // ------------------------------------------------------------------ state

    public ProductCatalog catalog() {
        return catalogStore.catalog();
    }

    public StockStore stock() {
        return stockStore;
    }

    public ShopSavedData dataOf(ServerPlayer player) {
        return ShopSavedData.get(player.serverLevel());
    }

    public ShopSavedData dataOf(ServerLevel level) {
        return ShopSavedData.get(level);
    }

    /** 管理员改库存：同时更新持久化数据与运行中的库存快照。 */
    public void updateStock(ServerLevel level, String productId, int quantity) {
        dataOf(level).setStock(productId, quantity);
        stockStore.set(productId, quantity);
    }

    /** 无服务端上下文时（启动早期/无 level）只替换目录。 */
    public void applyCatalogHeadless(ProductCatalog fresh) {
        catalogStore.set(fresh);
    }

    /** 数据包重载：换新目录、保留运行中库存、重新初始化新增有限库存商品。 */
    public void onCatalogReload(ProductCatalog fresh, ServerLevel level) {
        ShopSavedData data = dataOf(level);
        fresh = fresh.withMaxStacks(itemId -> {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            return item == null || item == BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("air"))
                    ? -1 : item.getDefaultMaxStackSize();
        });
        ProductCatalog finalFresh = fresh;
        for (Product product : fresh.products()) {
            if (product.stockMode() == StockMode.FINITE && data.stockOf(product.id()) < 0) {
                data.setStock(product.id(), product.stockQuantity());
            }
        }
        stockStore.clear();
        for (Map.Entry<String, Integer> entry : data.stock().entrySet()) {
            stockStore.set(entry.getKey(), entry.getValue());
        }
        stockStore.retainOnly(id -> finalFresh.product(id).isPresent());
        catalogStore.set(fresh);
        syncToAll(level.getServer().overworld());
        BuildShopMod.LOGGER.info("Shop catalog reloaded: {} products, {} categories",
                fresh.productCount(), fresh.categoryCount());
    }

    // ------------------------------------------------------------------ purchase

    public PurchaseResult purchase(ServerPlayer player, String productId, PurchaseMode mode,
                                   int requestedQuantity, String requestId) {
        ProductCatalog catalog = catalog();
        Product product = catalog.product(productId).orElse(null);
        if (product == null) {
            sendPurchaseResult(player, PurchaseResult.fail(com.tanrunn.buildshop.core.PurchaseFailure.PRODUCT_NOT_FOUND),
                    Map.of(), Map.of(), requestId);
            return PurchaseResult.fail(com.tanrunn.buildshop.core.PurchaseFailure.PRODUCT_NOT_FOUND);
        }

        if (!rateLimited(player, productId)) {
            PurchaseResult result = PurchaseResult.fail(com.tanrunn.buildshop.core.PurchaseFailure.RATE_LIMITED);
            sendPurchaseResult(player, result, Map.of(), Map.of(), requestId);
            return result;
        }

        ShopCurrencyProvider currency = currencyFor(product.currency());
        if (currency == null) {
            PurchaseResult result = PurchaseResult.fail(com.tanrunn.buildshop.core.PurchaseFailure.NO_CURRENCY_PROVIDER);
            sendPurchaseResult(player, result, Map.of(), Map.of(), requestId);
            return result;
        }

        ItemStack template = buildItemStack(product);
        if (template.isEmpty() && !product.itemId().equals("minecraft:air")) {
            PurchaseResult result = PurchaseResult.fail(com.tanrunn.buildshop.core.PurchaseFailure.INVALID_ITEM);
            sendPurchaseResult(player, result, Map.of(), Map.of(), requestId);
            return result;
        }

        Wallet wallet = new ProviderWallet(player, currency);
        ItemDispatcher dispatcher = new InventoryDispatcher(player, product, template);

        PlayerIdempotency idem = idempotencyByPlayer.computeIfAbsent(player.getUUID(), key -> new PlayerIdempotency());
        PurchaseEngine engine = new PurchaseEngine(catalog, stockStore, idem, Config.SERVER_MAX_PER_REQUEST.get());
        String idemKey = player.getUUID() + ":" + requestId;
        PurchaseResult result = engine.execute(new PurchaseRequest(productId, mode, requestedQuantity, idemKey), wallet, dispatcher);

        ShopSavedData data = dataOf(player);
        if (result.success()) {
            data.setDirty();
        }

        Map<String, Integer> stockUpdates = new LinkedHashMap<>();
        if (result.success() || result.failure() == com.tanrunn.buildshop.core.PurchaseFailure.INSUFFICIENT_STOCK) {
            stockUpdates.put(productId, stockStore.remaining(productId));
        }
        sendPurchaseResult(player, result, balanceSnapshot(player, product.currency()), stockUpdates, requestId);
        return result;
    }

    private boolean rateLimited(ServerPlayer player, String productId) {
        long now = player.serverLevel().getGameTime();
        Long last = lastRequestTick.get(player.getUUID());
        if (last != null && now - last < Config.PURCHASE_COOLDOWN_TICKS.get()) {
            return false;
        }
        lastRequestTick.put(player.getUUID(), now);
        return true;
    }

    private void sendPurchaseResult(ServerPlayer player, PurchaseResult result,
                                    Map<String, String> balances, Map<String, Integer> stockUpdates, String requestId) {
        PacketDistributor.sendToPlayer(player, new PurchaseResultPayload(
                requestId == null ? "" : requestId,
                result.success(),
                result.messageKey(),
                result.quantity(),
                result.totalPrice(),
                balances,
                stockUpdates
        ));
    }

    // ------------------------------------------------------------------ currency

    /** 按需注册物品货币提供者。 */
    public ShopCurrencyProvider currencyFor(String currencyId) {
        Optional<ShopCurrencyProvider> existing = CurrencyRegistry.INSTANCE.get(currencyId);
        if (existing.isPresent()) return existing.get();
        if (ItemCurrencyProvider.isItemCurrencyId(currencyId)) {
            String itemId = currencyId.substring("items:".length());
            if (ResourceLocation.tryParse(itemId) != null && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemId))) {
                ShopCurrencyProvider provider = new ItemCurrencyProvider(itemId);
                CurrencyRegistry.INSTANCE.register(provider);
                return provider;
            }
        }
        return null;
    }

    public String formatPrice(String currencyId, long amount) {
        ShopCurrencyProvider provider = currencyFor(currencyId);
        if (provider == null) return String.valueOf(amount);
        return provider.format(amount) + " " + provider.displayName();
    }

    // ------------------------------------------------------------------ sync

    public SyncShopPayload buildSync(ServerPlayer player) {
        List<CategoryDto> categories = new ArrayList<>();
        for (com.tanrunn.buildshop.core.Category category : catalog().categories()) {
            if (category.enabled()) categories.add(CategoryDto.from(category));
        }

        List<ProductDto> products = new ArrayList<>();
        for (Product product : catalog().enabledProducts()) {
            ItemStack template = buildItemStack(product);
            String expression = serializeStack(template);
            ProductDto dto = ProductDto.from(product, formatPrice(product.currency(), product.unitPrice()),
                    stockStore.remaining(product.id()))
                    .withItemExpression(expression);
            products.add(dto);
        }

        Map<String, String> balances = new LinkedHashMap<>();
        Map<String, Long> balanceAmounts = new LinkedHashMap<>();
        Map<String, String> currencyNames = new LinkedHashMap<>();
        String defaultCurrency = Config.DEFAULT_CURRENCY.get();
        for (String currencyId : catalogCurrencies(defaultCurrency)) {
            ShopCurrencyProvider provider = currencyFor(currencyId);
            if (provider == null) continue;
            balances.put(currencyId, provider.format(provider.balance(player)));
            balanceAmounts.put(currencyId, provider.balance(player));
            currencyNames.put(currencyId, provider.displayName());
        }
        return BuildShopNetwork.syncOf(categories, products, balances, balanceAmounts, currencyNames,
                defaultCurrency, Config.HIDE_EMPTY_CATEGORIES.get());
    }

    /** 目录中用到的货币集合（含默认货币），保证顺序稳定。 */
    private List<String> catalogCurrencies(String defaultCurrency) {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        ids.put(defaultCurrency, Boolean.TRUE);
        for (Product product : catalog().products()) {
            ids.put(product.currency(), Boolean.TRUE);
        }
        return new ArrayList<>(ids.keySet());
    }

    private Map<String, String> balanceSnapshot(ServerPlayer player, String currencyId) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(currencyId, formatBalance(player, currencyId));
        return result;
    }

    public String formatBalance(ServerPlayer player, String currencyId) {
        ShopCurrencyProvider provider = currencyFor(currencyId);
        if (provider == null) return "?";
        return provider.format(provider.balance(player));
    }

    // ------------------------------------------------------------------ items

    /** 由商品定义构建 ItemStack（应用组件）。 */
    public ItemStack buildItemStack(Product product) {
        ResourceLocation itemId = ResourceLocation.tryParse(product.itemId());
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        ItemStack stack = new ItemStack(item);
        if (product.components() != null && product.components().isJsonObject()) {
            DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, product.components())
                    .resultOrPartial(error -> BuildShopMod.LOGGER.warn("Invalid components for {}: {}", product.id(), error))
                    .ifPresent(stack::applyComponents);
        }
        return stack;
    }

    /** 序列化为 {@code {id,count,components}} SNBT，供 AUI &lt;item&gt; 元素渲染。 */
    public String serializeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                .resultOrPartial(error -> BuildShopMod.LOGGER.warn("Failed to serialize stack: {}", error))
                .orElse(null);
        return tag == null ? "minecraft:air" : tag.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** 有限库存：扣减后同步；无限库存：不扣减。 */
    public void syncToAll(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, buildSync(player));
        }
    }

    public void syncTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildSync(player));
    }

    /** 每个玩家的有界幂等存储。 */
    static final class PlayerIdempotency implements IdempotencyStore {
        private static final int MAX_ENTRIES = 256;

        private final LinkedHashMap<String, PurchaseResult> cache = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PurchaseResult> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        @Override
        public boolean contains(String key) {
            return cache.containsKey(key);
        }

        @Override
        public void putIfAbsent(String key, PurchaseResult result) {
            cache.putIfAbsent(key, result);
        }

        @Override
        public PurchaseResult get(String key) {
            return cache.get(key);
        }

        @Override
        public void remove(String key) {
            cache.remove(key);
        }
    }

    // ------------------------------------------------------------ adapters

    /** 把 {@link ShopCurrencyProvider} 适配为核心 {@link Wallet}。 */
    private static final class ProviderWallet implements Wallet {
        private final ServerPlayer player;
        private final ShopCurrencyProvider provider;

        ProviderWallet(ServerPlayer player, ShopCurrencyProvider provider) {
            this.player = player;
            this.provider = provider;
        }

        @Override
        public long balance() {
            return provider.balance(player);
        }

        @Override
        public boolean canWithdraw(long amount) {
            return provider.canWithdraw(player, amount);
        }

        @Override
        public boolean withdraw(long amount, String requestId) {
            PaymentResult result = provider.withdraw(player, amount, "shop_purchase", requestId);
            return result.success();
        }

        @Override
        public boolean refund(long amount, String requestId) {
            PaymentResult result = provider.refund(player, amount, "shop_rollback", requestId);
            return result.success();
        }
    }

    /** 把玩家背包适配为核心 {@link ItemDispatcher}。 */
    private static final class InventoryDispatcher implements ItemDispatcher {
        private final ServerPlayer player;
        private final Product product;
        private final ItemStack template;

        InventoryDispatcher(ServerPlayer player, Product product, ItemStack template) {
            this.player = player;
            this.product = product;
            this.template = template;
        }

        @Override
        public boolean canDispense(int quantity) {
            Inventory inventory = player.getInventory();
            int freeSlots = 0;
            List<FitCalculator.Slot> slots = new ArrayList<>(40);
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    freeSlots++;
                } else {
                    slots.add(new FitCalculator.Slot(stack.getCount(), stack.getMaxStackSize()));
                }
            }
            return FitCalculator.fits(slots, product.maxStack(), freeSlots, quantity);
        }

        @Override
        public boolean dispense(int quantity) {
            int remaining = quantity;
            List<ItemStack> given = new ArrayList<>();
            while (remaining > 0) {
                int chunk = Math.min(remaining, product.maxStack());
                ItemStack stack = template.copy();
                stack.setCount(chunk);
                boolean added = player.getInventory().add(stack);
                if (!added || !stack.isEmpty()) {
                    // 整笔失败：回滚所有已放入的物品（含部分放入）
                    if (added) {
                        player.getInventory().removeItem(stack);
                    }
                    for (ItemStack placed : given) {
                        player.getInventory().removeItem(placed);
                    }
                    return false;
                }
                given.add(stack);
                remaining -= chunk;
            }
            player.getInventory().setChanged();
            return true;
        }
    }
}
