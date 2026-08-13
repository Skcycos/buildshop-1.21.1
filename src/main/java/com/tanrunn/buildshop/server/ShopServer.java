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
import com.tanrunn.buildshop.core.PurchaseFailure;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.core.PurchaseRequest;
import com.tanrunn.buildshop.core.PurchaseResult;
import com.tanrunn.buildshop.core.StockMode;
import com.tanrunn.buildshop.core.StockReconciler;
import com.tanrunn.buildshop.core.StockStore;
import com.tanrunn.buildshop.core.Wallet;
import com.tanrunn.buildshop.network.BuildShopNetwork;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 商店服务端核心：目录、库存、购买事务、同步。
 *
 * <p>所有库存/余额变更都在服务端主线程完成（网络包经 {@code enqueueWork} 转入主线程，
 * 数据包 reload 的 apply 阶段也在主线程执行）。</p>
 */
public final class ShopServer {

    public static final ShopServer INSTANCE = new ShopServer();

    /** requestId 安全长度上限（网络层同步限制，防御超长字符串）。 */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

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
        return player == null ? null : ShopSavedData.get(player.serverLevel());
    }

    public ShopSavedData dataOf(ServerLevel level) {
        return ShopSavedData.get(level);
    }

    /** 管理员改库存：同时更新持久化数据与运行中的库存快照。 */
    public void updateStock(ServerLevel level, String productId, int quantity) {
        ShopSavedData data = dataOf(level);
        data.setStock(productId, quantity);
        stockStore.set(productId, quantity);
    }

    /** 无服务端上下文时（启动早期/无 level）只替换目录。 */
    public void applyCatalogHeadless(ProductCatalog fresh) {
        catalogStore.set(fresh);
    }

    /**
     * 服务器启动完成：把已加载目录原子应用到 overworld SavedData 与运行时 StockStore。
     * 修复首次启动时目录已加载但有限库存从未初始化的问题。
     */
    public void onServerStarted(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            BuildShopMod.LOGGER.warn("[Shop] onServerStarted: overworld not available, deferring stock init");
            return;
        }
        applyCatalog(catalog(), overworld);
    }

    /**
     * 数据包重载 / 服务器启动的统一原子应用入口：
     * 目录解析完成后一次性调用，不存在分类/商品两个 listener 各自应用导致的半成品目录。
     */
    public void applyCatalog(ProductCatalog fresh, ServerLevel level) {
        if (level == null || level.getServer() == null) {
            applyCatalogHeadless(fresh);
            return;
        }
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            ProductCatalog deferred = fresh;
            server.execute(() -> applyCatalog(deferred, level));
            return;
        }
        applyCatalogOnMainThread(fresh, level);
    }

    private void applyCatalogOnMainThread(ProductCatalog fresh, ServerLevel level) {
        ProductCatalog resolved = fresh.withMaxStacks(ShopServer::resolveDefaultMaxStack);
        ShopSavedData data = dataOf(level);

        Map<String, Integer> jsonInitial = new LinkedHashMap<>();
        Set<String> catalogIds = new HashSet<>();
        for (Product product : resolved.products()) {
            catalogIds.add(product.id());
            if (product.stockMode() == StockMode.FINITE) {
                jsonInitial.put(product.id(), product.stockQuantity());
            }
        }
        Map<String, Integer> reconciled = StockReconciler.reconcile(data.stock(), jsonInitial, catalogIds);
        if (!reconciled.equals(data.stock())) {
            data.replaceStock(reconciled);
        }

        stockStore.clear();
        reconciled.forEach(stockStore::set);
        catalogStore.set(resolved);

        ServerLevel overworld = level.getServer().overworld();
        if (overworld != null) {
            syncToAll(overworld);
        }
        BuildShopMod.LOGGER.info("Shop catalog applied: {} products, {} categories, {} finite stock entries",
                resolved.productCount(), resolved.categoryCount(), reconciled.size());
    }

    private static int resolveDefaultMaxStack(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return -1;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("air"))
                ? -1 : item.getDefaultMaxStackSize();
    }

    // ------------------------------------------------------------------ purchase

    public PurchaseResult purchase(ServerPlayer player, String productId, PurchaseMode mode,
                                   int requestedQuantity, String requestId) {
        if (!Config.ENABLED.get()) {
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.SHOP_DISABLED);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            BuildShopMod.LOGGER.warn("[Shop] rejected purchase with invalid requestId from {}: length={}",
                    player.getGameProfile().getName(), requestId == null ? 0 : requestId.length());
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.INVALID_REQUEST);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }

        ProductCatalog catalog = catalog();
        Product product = catalog.product(productId).orElse(null);
        if (product == null) {
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.PRODUCT_NOT_FOUND);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }

        ShopCurrencyProvider currency = currencyFor(product.currency());
        if (currency == null) {
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.NO_CURRENCY_PROVIDER);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }

        ItemStack template = buildItemStack(product);
        if (template.isEmpty() && !product.itemId().equals("minecraft:air")) {
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.INVALID_ITEM);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }

        Wallet wallet = new ProviderWallet(player, currency);
        ItemDispatcher dispatcher = new InventoryDispatcher(player, template);

        PlayerIdempotency idem = idempotencyByPlayer.computeIfAbsent(player.getUUID(), key -> new PlayerIdempotency());
        PurchaseEngine engine = new PurchaseEngine(catalog, stockStore, idem, Config.SERVER_MAX_PER_REQUEST.get());
        PurchaseRequest request = new PurchaseRequest(productId, mode, requestedQuantity, requestId);

        // 幂等重放优先：同 requestId 的重复请求（网络重试）直接回放结果，不再扣款/发货。
        // 注意与引擎内部的 key 保持一致（引擎按玩家作用域存储，key 不含玩家前缀）。
        String idemKey = PurchaseEngine.idempotencyKey(request);
        PurchaseResult cached = idem.get(idemKey);
        if (cached != null) {
            Map<String, Integer> stockUpdates = new LinkedHashMap<>();
            if (product.stockMode() == StockMode.FINITE) {
                stockUpdates.put(productId, stockStore.remaining(productId));
            }
            sendPurchaseResult(player, cached, balanceSnapshot(player, product.currency()),
                    balanceAmountSnapshot(player, product.currency()), stockUpdates, requestId);
            return cached;
        }

        if (!rateLimited(player)) {
            PurchaseResult result = PurchaseResult.fail(PurchaseFailure.RATE_LIMITED);
            sendPurchaseResult(player, result, Map.of(), Map.of(), Map.of(), requestId);
            return result;
        }

        PurchaseResult result = engine.execute(request, wallet, dispatcher);

        ShopSavedData data = dataOf(player);
        if (result.success() && product.stockMode() == StockMode.FINITE) {
            data.setStock(productId, stockStore.remaining(productId));
        }

        Map<String, Integer> stockUpdates = new LinkedHashMap<>();
        if (result.success() || result.failure() == PurchaseFailure.INSUFFICIENT_STOCK) {
            stockUpdates.put(productId, stockStore.remaining(productId));
        }
        sendPurchaseResult(player, result, balanceSnapshot(player, product.currency()),
                balanceAmountSnapshot(player, product.currency()), stockUpdates, requestId);
        return result;
    }

    private boolean rateLimited(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        Long last = lastRequestTick.get(player.getUUID());
        if (last != null && now - last < Config.PURCHASE_COOLDOWN_TICKS.get()) {
            return false;
        }
        lastRequestTick.put(player.getUUID(), now);
        return true;
    }

    /** 玩家退出：清理玩家级幂等缓存与节流状态，避免无限保留 UUID。 */
    public void onPlayerLoggedOut(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        idempotencyByPlayer.remove(uuid);
        lastRequestTick.remove(uuid);
    }

    /** 服务器停止：清空全部运行时状态。 */
    public void onServerStopped() {
        idempotencyByPlayer.clear();
        lastRequestTick.clear();
    }

    private void sendPurchaseResult(ServerPlayer player, PurchaseResult result,
                                    Map<String, String> balances, Map<String, Long> balanceAmounts,
                                    Map<String, Integer> stockUpdates, String requestId) {
        if (!isReachable(player, PurchaseResultPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new PurchaseResultPayload(
                requestId == null ? "" : requestId,
                result.success(),
                result.messageKey(),
                result.quantity(),
                result.totalPrice(),
                balances,
                balanceAmounts,
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

    private Map<String, Long> balanceAmountSnapshot(ServerPlayer player, String currencyId) {
        Map<String, Long> result = new LinkedHashMap<>();
        ShopCurrencyProvider provider = currencyFor(currencyId);
        if (provider != null) {
            result.put(currencyId, provider.balance(player));
        }
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

    /**
     * 网络连接有效且已注册本 Mod 频道时才发包。
     * 跳过：未完成登录/已登出的玩家，以及没有真实网络会话的测试 mock 玩家。
     */
    private static boolean isReachable(ServerPlayer player, CustomPacketPayload.Type<?> payloadType) {
        return player != null && player.connection != null
                && player.connection.getConnection() != null
                && player.connection.getConnection().isConnected()
                && NetworkRegistry.hasChannel(player.connection, payloadType.id());
    }

    /** 有限库存：扣减后同步；无限库存：不扣减。 */
    public void syncToAll(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!isReachable(player, SyncShopPayload.TYPE)) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, buildSync(player));
        }
    }

    public void syncTo(ServerPlayer player) {
        if (!isReachable(player, SyncShopPayload.TYPE)) {
            return;
        }
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

    /**
     * 把玩家背包适配为核心 {@link ItemDispatcher}。
     *
     * <p>容量只统计空槽位 + 与目标 ItemStack 物品和 Data Components 完全兼容的已有堆叠；
     * 最大堆叠以构造完成后的真实 {@link ItemStack#getMaxStackSize()} 为准（组件可修改）。</p>
     */
    static final class InventoryDispatcher implements ItemDispatcher {
        private final ServerPlayer player;
        private final ItemStack template;

        InventoryDispatcher(ServerPlayer player, ItemStack template) {
            this.player = player;
            this.template = template;
        }

        @Override
        public boolean canDispense(int quantity) {
            Inventory inventory = player.getInventory();
            int freeSlots = 0;
            List<FitCalculator.Slot> slots = new ArrayList<>(40);
            for (int i = 0; i < inventory.items.size(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    freeSlots++;
                } else if (ItemStack.isSameItemSameComponents(template, stack)) {
                    slots.add(new FitCalculator.Slot(stack.getCount(), stack.getMaxStackSize(), true));
                }
                // 其他物品 / 不同组件的堆叠：不占用目标商品可用空间。
            }
            return FitCalculator.fits(slots, template.getMaxStackSize(), freeSlots, quantity);
        }

        @Override
        public boolean dispense(int quantity) {
            int remaining = quantity;
            int placedCount = 0;
            while (remaining > 0) {
                int chunk = Math.min(remaining, template.getMaxStackSize());
                ItemStack stack = template.copy();
                stack.setCount(chunk);
                // Inventory.add 会修改传入 stack（放入后被清空/减少），不能拿它当回滚凭据。
                boolean added = player.getInventory().add(stack);
                int placed = chunk - stack.getCount();
                if (placed > 0) {
                    placedCount += placed;
                }
                if (!added || placed <= 0) {
                    // 整笔失败：按数量移除已放入的物品（含部分放入），不留部分商品。
                    removeMatching(placedCount);
                    return false;
                }
                remaining -= placed;
            }
            player.getInventory().setChanged();
            return true;
        }

        private void removeMatching(int count) {
            if (count <= 0) return;
            Inventory inventory = player.getInventory();
            int remaining = count;
            for (int i = 0; i < inventory.items.size() && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(template, stack)) continue;
                int taken = Math.min(stack.getCount(), remaining);
                stack.shrink(taken);
                remaining -= taken;
            }
        }
    }
}
