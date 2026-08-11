package com.tanrunn.buildshop.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.sighs.apricityui.spi.AuiServices;
import com.sighs.apricityui.task.FrameTaskScheduler;
import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.core.Category;
import com.tanrunn.buildshop.core.FitCalculator;
import com.tanrunn.buildshop.core.ItemExpressionUtil;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseRequestPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.RequestSyncPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 商店页面 Java 绑定（AUI）。
 *
 * <p>页面脚本保持最少：所有 DOM 操作从 Java 侧发起，且全部在客户端线程执行。
 * 商品卡片创建后复用 DOM 节点，状态更新只改价格/库存/按钮相关文本。</p>
 */
public final class ShopScreenController {

    public static final ShopScreenController INSTANCE = new ShopScreenController();
    public static final String TEMPLATE_PATH = "buildingshop/screens/building_shop.html";

    private Document document;
    private long generation;
    private SyncShopPayload lastSync;
    private final Map<String, String> balances = new LinkedHashMap<>();
    private final Map<String, Long> balanceAmounts = new LinkedHashMap<>();
    private final Map<String, String> currencyNames = new LinkedHashMap<>();
    private String defaultCurrency = Category.ALL_ID;
    private String selectedCategory = Category.ALL_ID;
    private String searchText = "";
    private String qtyDialogProductId;
    private SortMode sortMode = SortMode.DEFAULT;
    private boolean hideEmptyCategories = true;

    private ShopScreenController() {
    }

    private enum SortMode {
        DEFAULT, PRICE_ASC, PRICE_DESC
    }

    // ------------------------------------------------------------------ open

    public void open() {
        if (Minecraft.getInstance().screen instanceof ApricityScreen) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        AuiServices.client().openScreen(TEMPLATE_PATH);
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof ApricityScreen apricityScreen) {
            bind(apricityScreen.getLinkedDocument());
        }
        PacketDistributor.sendToServer(new RequestSyncPayload());
    }

    private void bind(Document linked) {
        this.document = linked;
        if (linked == null) {
            BuildShopMod.LOGGER.error("[ShopUI] template missing: {}", TEMPLATE_PATH);
            return;
        }
        this.generation = linked.getRefreshGeneration();
        rebind();
    }

    private void rebind() {
        Document doc = document;
        if (doc == null) return;

        Element categories = doc.getElementById("categories");
        Element products = doc.getElementById("products");
        Element search = doc.getElementById("search");
        Element refresh = doc.getElementById("refresh");
        Element hint = doc.getElementById("hint");
        Element balance = doc.getElementById("balance");
        Element balanceLabel = doc.getElementById("balance-label");
        Element productCount = doc.getElementById("product-count");
        Element status = doc.getElementById("status");
        Element qtyDialog = doc.getElementById("qty-dialog");
        Element qtyInput = doc.getElementById("qty-input");
        Element qtyConfirm = doc.getElementById("qty-confirm");
        Element qtyCancel = doc.getElementById("qty-cancel");

        if (categories == null || products == null) {
            BuildShopMod.LOGGER.error("[ShopUI] required elements missing in template {}", TEMPLATE_PATH);
            return;
        }

        if (hint != null) {
            hint.setTextContent(Component.translatable("buildshop.ui.hint").getString());
        }
        if (search != null) {
            search.addEventListener("input", event -> {
                this.searchText = search.getValue() == null ? "" : search.getValue();
                renderProducts();
            });
        }
        if (refresh != null) {
            refresh.addEventListener("click", event -> PacketDistributor.sendToServer(new RequestSyncPayload()));
        }
        Element sortButtons = doc.getElementById("sort-buttons");
        if (sortButtons != null) {
            sortButtons.addEventListener("click", event -> onSortClick(event));
        }
        if (qtyInput != null) {
            qtyInput.addEventListener("input", event -> updateQtyEstimates());
            qtyInput.addEventListener("change", event -> updateQtyEstimates());
        }
        if (qtyConfirm != null) {
            qtyConfirm.addEventListener("click", event -> confirmQtyPurchase());
        }
        if (qtyCancel != null) {
            qtyCancel.addEventListener("click", event -> hideQtyDialog());
        }

        categories.addEventListener("click", event -> onCategoryClick(event));
        products.addEventListener("click", event -> onCardClick(event));
        products.addEventListener("contextmenu", event -> onCardContextMenu(event));

        if (lastSync != null) {
            renderAll();
        } else {
            showStatus(Component.translatable("buildshop.ui.syncing").getString(), false);
        }
    }

    // ------------------------------------------------------------------ sync

    public void applySync(SyncShopPayload payload) {
        if (payload == null) return;
        this.lastSync = payload;
        this.balances.clear();
        this.balances.putAll(payload.balances());
        this.balanceAmounts.clear();
        this.balanceAmounts.putAll(payload.balanceAmounts());
        this.currencyNames.clear();
        this.currencyNames.putAll(payload.currencyNames());
        this.defaultCurrency = payload.defaultCurrency();
        this.hideEmptyCategories = payload.hideEmptyCategories();
        if (document == null) return;
        renderAll();
    }

    private void renderAll() {
        Document doc = document;
        if (doc == null || lastSync == null) return;
        renderBalance(doc);
        renderCategories(doc);
        renderProducts();
    }

    private void renderBalance(Document doc) {
        Element balance = doc.getElementById("balance");
        Element label = doc.getElementById("balance-label");
        if (balance != null) {
            balance.setTextContent(balances.getOrDefault(defaultCurrency, "—"));
        }
        if (label != null) {
            String name = currencyNames.getOrDefault(defaultCurrency, defaultCurrency);
            label.setTextContent("余额 · " + name);
        }
    }

    private void renderCategories(Document doc) {
        Element container = doc.getElementById("categories");
        if (container == null) return;
        container.clearChildren();

        String allName = Component.translatable("buildshop.ui.category.all").getString();
        container.appendChild(createCategoryButton(doc, Category.ALL_ID, allName, null));

        Map<String, Integer> productCountByCategory = new HashMap<>();
        for (ProductDto product : lastSync.products()) {
            for (String category : product.categories()) {
                productCountByCategory.merge(category, 1, Integer::sum);
            }
        }

        for (CategoryDto category : lastSync.categories()) {
            if (hideEmptyCategories && !productCountByCategory.containsKey(category.id())) {
                continue; // 空分类默认隐藏（配置可关）
            }
            container.appendChild(createCategoryButton(doc, category.id(), category.name(), category.iconExpression()));
        }
    }

    private Element createCategoryButton(Document doc, String id, String name, String iconExpression) {
        Element button = doc.createElement("div");
        button.setAttribute("class", "cat" + (id.equals(selectedCategory) ? " active" : ""));
        button.setAttribute("data-cat", id);

        if (iconExpression != null && !iconExpression.isBlank()) {
            Element icon = doc.createElement("item");
            icon.setAttribute("class", "cat-icon");
            icon.setTextContent(iconExpression);
            button.appendChild(icon);
        }
        Element text = doc.createElement("div");
        text.setAttribute("class", "cat-name");
        text.setTextContent(name);
        button.appendChild(text);
        return button;
    }

    // ------------------------------------------------------------------ products

    private void renderProducts() {
        Document doc = document;
        if (doc == null || lastSync == null) return;

        Element container = doc.getElementById("products");
        Element countEl = doc.getElementById("product-count");
        if (container == null) return;

        Map<String, Element> existing = new LinkedHashMap<>();
        for (Element child : container.children) {
            String id = child.getAttribute("data-id");
            if (id != null) existing.put(id, child);
        }

        List<ProductDto> ordered = new ArrayList<>(lastSync.products());
        ordered.sort((a, b) -> switch (sortMode) {
            case PRICE_ASC -> Long.compare(a.unitPrice(), b.unitPrice());
            case PRICE_DESC -> Long.compare(b.unitPrice(), a.unitPrice());
            default -> 0;
        });

        int visible = 0;
        for (ProductDto product : ordered) {
            boolean matches = matchesFilter(product);
            Element card = existing.remove(product.id());
            if (card == null) {
                card = createCard(doc, product);
            }
            updateCard(card, product);
            card.setInlineStyleProperty("display", matches ? "flex" : "none");
            // 重新 append：排序时移动已有节点（复用 DOM，不重建）
            container.appendChild(card);
            if (matches) visible++;
        }
        for (Element removed : existing.values()) {
            removed.remove();
        }

        if (countEl != null) {
            String all = Component.translatable("buildshop.ui.count", visible, lastSync.products().size()).getString();
            countEl.setTextContent(all);
        }

        Element empty = doc.getElementById("products-empty");
        if (empty == null) {
            empty = doc.createElement("div");
            empty.setAttribute("id", "products-empty");
            empty.setAttribute("class", "empty");
            empty.setTextContent(Component.translatable("buildshop.ui.empty").getString());
        }
        container.appendChild(empty); // 每次移到末尾，避免被排序打乱
        empty.setInlineStyleProperty("display", visible == 0 ? "block" : "none");
    }

    private boolean matchesFilter(ProductDto product) {
        if (!Category.ALL_ID.equals(selectedCategory) && !product.categories().contains(selectedCategory)) {
            return false;
        }
        if (searchText != null && !searchText.isBlank()) {
            String query = searchText.toLowerCase(Locale.ROOT);
            if (!product.displayName().toLowerCase(Locale.ROOT).contains(query)
                    && !product.id().toLowerCase(Locale.ROOT).contains(query)
                    && !(product.description() != null && product.description().toLowerCase(Locale.ROOT).contains(query))) {
                return false;
            }
        }
        return true;
    }

    private Element createCard(Document doc, ProductDto product) {
        Element card = doc.createElement("div");
        card.setAttribute("class", "card");
        card.setAttribute("data-id", product.id());

        Element icon = doc.createElement("item");
        icon.setAttribute("class", "card-icon");
        card.appendChild(icon);

        Element name = doc.createElement("div");
        name.setAttribute("class", "card-name");
        card.appendChild(name);

        Element desc = doc.createElement("div");
        desc.setAttribute("class", "card-desc");
        card.appendChild(desc);

        Element row = doc.createElement("div");
        row.setAttribute("class", "card-row");

        Element price = doc.createElement("span");
        price.setAttribute("class", "card-price");
        row.appendChild(price);

        Element stock = doc.createElement("span");
        stock.setAttribute("class", "card-stock");
        row.appendChild(stock);
        card.appendChild(row);

        Element state = doc.createElement("div");
        state.setAttribute("class", "card-state");
        state.setInlineStyleProperty("display", "none");
        card.appendChild(state);
        return card;
    }

    private void updateCard(Element card, ProductDto product) {
        Element icon = card.children.isEmpty() ? null : card.children.get(0);
        if (icon != null) {
            String expression = resolveItemExpression(product);
            icon.setTextContent(expression);
        }
        Element name = card.children.size() > 1 ? card.children.get(1) : null;
        if (name != null) {
            name.setTextContent(product.displayName());
        }
        Element desc = card.children.size() > 2 ? card.children.get(2) : null;
        if (desc != null) {
            if (!itemAvailable(product)) {
                desc.setTextContent(Component.translatable("buildshop.ui.item_unavailable").getString());
            } else {
                desc.setTextContent(product.description() == null ? "" : product.description());
            }
        }
        Element row = card.children.size() > 3 ? card.children.get(3) : null;
        if (row != null) {
            Element price = row.children.isEmpty() ? null : row.children.get(0);
            Element stock = row.children.size() > 1 ? row.children.get(1) : null;
            if (price != null) {
                price.setTextContent(product.formattedPrice());
            }
            if (stock != null) {
                if (product.stockMode() == com.tanrunn.buildshop.core.StockMode.INFINITE) {
                    stock.setTextContent(Component.translatable("buildshop.ui.stock.infinite").getString());
                    stock.setAttribute("class", "card-stock");
                } else {
                    int remaining = product.stockRemaining();
                    stock.setTextContent(Component.translatable("buildshop.ui.stock.remaining", Math.max(0, remaining)).getString());
                    stock.setAttribute("class", "card-stock" + (remaining <= 64 ? " low" : ""));
                }
            }
        }
        Element state = card.children.size() > 4 ? card.children.get(4) : null;
        if (state != null) {
            String warning = cardWarning(product);
            state.setTextContent(warning == null ? "" : warning);
            state.setInlineStyleProperty("display", warning == null ? "none" : "block");
        }
        card.setAttribute("class", "card" + (product.enabled() ? "" : " disabled"));
    }

    /** 余额不足 / 背包空间不足提示（客户端估算，服务端仍是权威）。 */
    private String cardWarning(ProductDto product) {
        if (!product.enabled()) return null;
        Long balance = balanceAmounts.get(product.currency());
        if (balance != null && balance < product.unitPrice()) {
            return Component.translatable("buildshop.ui.warn.balance").getString();
        }
        if (freeSpaceFor(product) < 1) {
            return Component.translatable("buildshop.ui.warn.inventory").getString();
        }
        return null;
    }

    private int freeSpaceFor(ProductDto product) {
        net.minecraft.world.entity.player.Inventory inventory = Minecraft.getInstance().player == null
                ? null
                : Minecraft.getInstance().player.getInventory();
        if (inventory == null) return 0;
        int freeSlots = 0;
        List<FitCalculator.Slot> slots = new ArrayList<>(40);
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                freeSlots++;
            } else {
                slots.add(new FitCalculator.Slot(stack.getCount(), stack.getMaxStackSize()));
            }
        }
        return FitCalculator.capacity(slots, product.maxStack(), freeSlots);
    }

    private boolean itemAvailable(ProductDto product) {
        if (product.itemId() == null || product.itemId().isBlank()) return false;
        ResourceLocation id = ResourceLocation.tryParse(product.itemId());
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private String resolveItemExpression(ProductDto product) {
        return ItemExpressionUtil.resolveExpression(product.itemId(), product.itemExpression(), itemAvailable(product));
    }

    // ------------------------------------------------------------------ clicks

    private void onSortClick(Event event) {
        Element target = elementOf(event);
        if (target == null) return;
        Element button = walkUp(target, "data-sort");
        if (button == null) return;
        String value = button.getAttribute("data-sort");
        if (value == null) return;
        this.sortMode = switch (value) {
            case "price_asc" -> SortMode.PRICE_ASC;
            case "price_desc" -> SortMode.PRICE_DESC;
            default -> SortMode.DEFAULT;
        };
        Document doc = document;
        if (doc == null) return;
        Element container = doc.getElementById("sort-buttons");
        if (container != null) {
            for (Element child : container.children) {
                String sort = child.getAttribute("data-sort");
                boolean active = sort != null && sort.equals(value);
                child.setAttribute("class", "sort-btn" + (active ? " active" : ""));
            }
        }
        renderProducts();
    }

    private void onCategoryClick(Event event) {
        Element target = elementOf(event);
        if (target == null) return;
        Element button = walkUp(target, "data-cat");
        if (button == null) return;
        String id = button.getAttribute("data-cat");
        if (id == null) return;
        this.selectedCategory = id;
        renderCategories(document);
        renderProducts();
    }

    private void onCardClick(Event event) {
        Element card = cardFrom(event);
        if (card == null) return;
        String productId = card.getAttribute("data-id");
        if (productId == null) return;

        PurchaseMode mode;
        if (event instanceof MouseEvent mouse && mouse.controlKey) {
            mode = PurchaseMode.MAX;
        } else if (event instanceof MouseEvent mouse && mouse.shiftKey) {
            mode = PurchaseMode.BULK;
        } else {
            mode = PurchaseMode.SINGLE;
        }
        sendPurchase(productId, mode, 0);
    }

    private void onCardContextMenu(Event event) {
        Element card = cardFrom(event);
        if (card == null) return;
        String productId = card.getAttribute("data-id");
        if (productId == null) return;
        ProductDto product = productById(productId);
        if (product == null) return;
        openQtyDialog(product);
    }

    private ProductDto productById(String id) {
        if (lastSync == null) return null;
        for (ProductDto product : lastSync.products()) {
            if (product.id().equals(id)) return product;
        }
        return null;
    }

    private void sendPurchase(String productId, PurchaseMode mode, int quantity) {
        PacketDistributor.sendToServer(new PurchaseRequestPayload(
                productId,
                (byte) mode.ordinal(),
                quantity,
                UUID.randomUUID().toString()
        ));
    }

    private Element cardFrom(Event event) {
        Element target = elementOf(event);
        if (target == null) return null;
        Element card = walkUp(target, "data-id");
        if (card == null) return null;
        String classes = card.getAttribute("class");
        return classes != null && classes.startsWith("card") ? card : null;
    }

    private Element elementOf(Event event) {
        Object target = event.target;
        return target instanceof Element element ? element : null;
    }

    private Element walkUp(Element start, String attribute) {
        Element current = start;
        while (current != null) {
            if (current.getAttribute(attribute) != null) return current;
            current = current.parentElement;
        }
        return null;
    }

    // ------------------------------------------------------------------ qty dialog

    private void openQtyDialog(ProductDto product) {
        Document doc = document;
        if (doc == null) return;
        qtyDialogProductId = product.id();
        Element dialog = doc.getElementById("qty-dialog");
        Element title = doc.getElementById("qty-title");
        Element unit = doc.getElementById("qty-unit");
        Element input = doc.getElementById("qty-input");
        if (dialog == null || title == null || input == null) return;

        title.setTextContent(product.displayName());
        if (unit != null) {
            unit.setTextContent(product.formattedPrice() + " / 个");
        }
        input.setValue("1");
        dialog.setAttribute("class", "overlay");
        updateQtyEstimates();
    }

    private void hideQtyDialog() {
        Document doc = document;
        if (doc == null) return;
        qtyDialogProductId = null;
        Element dialog = doc.getElementById("qty-dialog");
        if (dialog != null) {
            dialog.setAttribute("class", "overlay hidden");
        }
    }

    private void updateQtyEstimates() {
        Document doc = document;
        if (doc == null || qtyDialogProductId == null) return;
        ProductDto product = productById(qtyDialogProductId);
        Element totalEl = doc.getElementById("qty-total");
        Element spaceEl = doc.getElementById("qty-space");
        Element input = doc.getElementById("qty-input");
        if (product == null || totalEl == null || input == null) return;

        int quantity = parseQuantity(input.getValue());
        long total;
        try {
            total = Math.multiplyExact(product.unitPrice(), Math.max(0, quantity));
        } catch (ArithmeticException overflow) {
            total = Long.MAX_VALUE;
        }
        totalEl.setTextContent(Component.translatable("buildshop.ui.qty.total", total).getString());
        if (spaceEl != null) {
            int stacks = (quantity + product.maxStack() - 1) / Math.max(1, product.maxStack());
            spaceEl.setTextContent(Component.translatable("buildshop.ui.qty.space", stacks).getString());
        }
    }

    private void confirmQtyPurchase() {
        Document doc = document;
        if (doc == null || qtyDialogProductId == null) return;
        Element input = doc.getElementById("qty-input");
        int quantity = input == null ? 0 : parseQuantity(input.getValue());
        if (quantity <= 0) {
            showStatus(Component.translatable("buildshop.ui.qty.invalid").getString(), true);
            return;
        }
        String productId = qtyDialogProductId;
        hideQtyDialog();
        sendPurchase(productId, PurchaseMode.CUSTOM, quantity);
    }

    private static int parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ purchase result

    public void applyPurchaseResult(PurchaseResultPayload payload) {
        Document doc = document;
        if (payload.balances() != null) {
            balances.putAll(payload.balances());
        }
        if (payload.stockUpdates() != null) {
            payload.stockUpdates().forEach((productId, remaining) -> updateStockLabel(doc, productId, remaining));
        }
        if (doc != null) {
            renderBalance(doc);
        }

        String message;
        if (payload.success()) {
            message = Component.translatable("buildshop.result.success", payload.quantity(), payload.totalPrice()).getString();
            showStatus(message, false);
        } else {
            String key = payload.messageKey() == null ? "buildshop.result.unknown" : payload.messageKey();
            message = Component.translatable(key).getString();
            showStatus(message, true);
        }
        if (qtyDialogProductId != null) {
            hideQtyDialog();
        }
    }

    private void updateStockLabel(Document doc, String productId, int remaining) {
        if (doc == null || lastSync == null) return;
        Element container = doc.getElementById("products");
        if (container == null) return;
        for (Element child : container.children) {
            if (productId.equals(child.getAttribute("data-id"))) {
                ProductDto product = productById(productId);
                if (product != null) {
                    ProductDto updated = new ProductDto(
                            product.id(), product.itemId(), product.itemExpression(), product.displayName(),
                            product.description(), product.currency(), product.unitPrice(), product.formattedPrice(),
                            product.bulkSize(), product.maxStack(), product.stockMode(), remaining,
                            product.enabled(), product.categories(), product.sort());
                    updateCard(child, updated);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ status

    private void showStatus(String message, boolean error) {
        Document doc = document;
        if (doc == null) return;
        Element status = doc.getElementById("status");
        if (status == null) return;
        status.setTextContent(message);
        status.setAttribute("class", "status " + (error ? "error" : "success"));
        long gen = generation;
        FrameTaskScheduler.scheduleAfterFrames(120, deadlineNs -> {
            Document current = doc;
            if (current != null && current.isCurrentGeneration(gen)) {
                Element el = current.getElementById("status");
                if (el != null) {
                    el.setAttribute("class", "status hidden");
                }
            }
            return true;
        });
    }
}
