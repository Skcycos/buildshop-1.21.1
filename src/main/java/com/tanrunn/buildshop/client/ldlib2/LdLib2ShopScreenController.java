package com.tanrunn.buildshop.client.ldlib2;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.math.Size;
import com.tanrunn.buildshop.client.ClientItemStackResolver;
import com.tanrunn.buildshop.client.ClientShopState;
import com.tanrunn.buildshop.client.ShopClientModel;
import com.tanrunn.buildshop.core.Category;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.CategoryDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.RequestSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;
import org.appliedenergistics.yoga.YogaPositionType;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Native LDLib2 building shop screen. Product rows are virtualized, so a large datapack does not
 * create one permanently mounted UI subtree per product.
 */
public final class LdLib2ShopScreenController {
    private static final int MAX_CUSTOM_QUANTITY = 2_147_483_647;
    /** Eight columns preserve a compact catalog without making item names hard to read. */
    private static final int PRODUCTS_PER_ROW = 8;
    /** Minecraft entity yaw corresponding to the fixed LDLib2 preview camera's front view. */
    private static final float ENTITY_MODEL_YAW = 135.0f;

    private final ClientShopState state;
    private final Runnable openDashboard;
    private ModularUIScreen screen;
    private ModularUI modularUI;
    private VirtualScrollerView<List<ProductDto>> productsView;
    private ScrollerView categoriesView;
    private Label balanceLabel;
    private Label countLabel;
    private Label statusLabel;
    private TextField searchField;
    private UIElement quantityOverlay;
    private TextField quantityField;
    private String selectedCategory = Category.ALL_ID;
    private String searchText = "";
    private ClientShopState.SortMode sortMode = ClientShopState.SortMode.DEFAULT;
    private String quantityProductId;

    public LdLib2ShopScreenController(ClientShopState state, Runnable openDashboard) {
        this.state = state;
        this.openDashboard = openDashboard;
    }

    public void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (screen != null && minecraft.screen == screen) {
            minecraft.setScreen(null);
            return;
        }
        UIElement root = buildRoot();
        modularUI = ModularUI.of(UI.of(root, LdLib2ShopScreenController::fitToScreen));
        screen = new ModularUIScreen(modularUI, Component.translatable("buildshop.ui.ldlib2.title"));
        minecraft.setScreen(screen);
        refreshFromState();
    }

    public void refreshFromState() {
        if (screen == null || Minecraft.getInstance().screen != screen) return;
        renderBalance();
        renderCategories();
        rebuildProducts();
    }

    public void applyPurchaseResult(PurchaseResultPayload payload) {
        ClientShopState.PurchaseFeedback feedback = state.applyPurchaseResult(payload);
        if (screen == null || Minecraft.getInstance().screen != screen) return;
        refreshFromState();
        if (feedback.success()) {
            showStatus(Component.translatable("buildshop.result.success", feedback.quantity(), feedback.totalPrice()).getString());
        } else {
            showStatus(Component.translatable(feedback.messageKey()).getString());
        }
        hideQuantityDialog();
    }

    public void applyShopDisabled() {
        showStatus(Component.translatable("buildshop.ui.disabled").getString());
    }

    private UIElement buildRoot() {
        UIElement root = new UIElement()
                .layout(layout -> layout.width(scale(820)).height(scale(560)).flexDirection(YogaFlexDirection.COLUMN)
                        .paddingAll(scale(10)).gapAll(scale(6)))
                .style(style -> style.background(Sprites.BORDER));

        // Keep every shop control in one compact title bar. The old dedicated toolbar left the
        // search field far wider than its job requires and took vertical room from the products.
        UIElement header = row(scale(5), scale(34));
        Label title = label(Component.translatable("buildshop.ui.ldlib2.title"));
        title.layout(layout -> layout.width(scale(100)).flexShrink(0));
        header.addChild(title);
        balanceLabel = label("—");
        balanceLabel.layout(layout -> layout.width(scale(130)).flexShrink(0));
        header.addChild(balanceLabel);
        Label searchLabel = label(Component.translatable("buildshop.ui.search"));
        searchLabel.layout(layout -> layout.width(scale(36)).flexShrink(0));
        header.addChild(searchLabel);
        searchField = new TextField().setAnyString();
        searchField.textFieldStyle(style -> style.fontSize(scale(9)).textShadow(false));
        searchField.layout(layout -> layout.width(scale(150)).height(scale(26)).flexShrink(1));
        searchField.setTextResponder(value -> {
            searchText = value == null ? "" : value;
            rebuildProducts();
        });
        header.addChild(searchField);
        header.addChild(button(Component.translatable("buildshop.ui.sort.default"), event -> setSort(ClientShopState.SortMode.DEFAULT), 64));
        header.addChild(button(Component.translatable("buildshop.ui.sort.asc"), event -> setSort(ClientShopState.SortMode.PRICE_ASC), 64));
        header.addChild(button(Component.translatable("buildshop.ui.sort.desc"), event -> setSort(ClientShopState.SortMode.PRICE_DESC), 64));
        header.addChild(button(Component.translatable("buildshop.ui.refresh"), event -> requestSync(), 60));
        header.addChild(button(Component.translatable("buildshop.ui.dashboard"), event -> openDashboard.run(), 76));
        root.addChild(header);

        UIElement body = row(scale(8), 0);
        body.layout(layout -> layout.flex(1).minWidth(0).minHeight(0).alignItems(YogaAlign.STRETCH));
        categoriesView = new ScrollerView();
        // The rail follows the available shop width.  The scroller itself owns the scrollbar
        // column, so buttons size to its viewport rather than competing with that column.
        categoriesView.layout(layout -> layout.widthPercent(14)
                .minWidth(scale(64)).maxWidth(scale(84)).flexShrink(1));
        categoriesView.style(style -> style.background(Sprites.BORDER1));
        categoriesView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL)
                .horizontalScrollDisplay(ScrollDisplay.NEVER));
        body.addChild(categoriesView);

        productsView = new VirtualScrollerView<>();
        productsView.layout(layout -> layout.flex(1).width(0).minWidth(0).minHeight(0));
        productsView.style(style -> style.background(Sprites.BORDER1));
        productsView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        productsView.virtualScrollerViewStyle(style -> style.estimatedItemHeight(scale(80)).overscanPixels(scale(160)));
        body.addChild(productsView);
        root.addChild(body);

        UIElement footer = row(scale(5), scale(26));
        countLabel = label("—");
        countLabel.layout(layout -> layout.width(scale(130)).flexShrink(0));
        footer.addChild(countLabel);
        Label shortcuts = label(Component.translatable("buildshop.ui.hint"));
        shortcuts.layout(layout -> layout.flex(2).minWidth(0));
        shortcuts.textStyle(style -> style.fontSize(scale(7)).textColor(0xFFB8B2A7).textShadow(false)
                .textWrap(TextWrap.HIDE).textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        footer.addChild(shortcuts);
        statusLabel = label("");
        statusLabel.layout(layout -> layout.flex(1).minWidth(0));
        footer.addChild(statusLabel);
        root.addChild(footer);

        quantityOverlay = new UIElement()
                .layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE).left(scale(270)).top(scale(190))
                        .width(scale(280)).height(scale(126)).paddingAll(scale(12)).gapAll(scale(6))
                        .alignItems(YogaAlign.STRETCH))
                .style(style -> style.background(Sprites.BORDER_THICK));
        quantityOverlay.setDisplay(false);
        Label quantityTitle = label(Component.translatable("buildshop.ui.qty.title"));
        quantityTitle.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(18)));
        quantityTitle.textStyle(style -> style.textAlignHorizontal(Horizontal.LEFT)
                .textAlignVertical(Vertical.CENTER));
        quantityOverlay.addChild(quantityTitle);
        quantityField = new TextField().setNumbersOnlyInt(1, MAX_CUSTOM_QUANTITY);
        quantityField.textFieldStyle(style -> style.fontSize(scale(9)).textShadow(false));
        quantityField.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(24)));
        quantityOverlay.addChild(quantityField);
        UIElement quantityButtons = row(scale(5), scale(26));
        quantityButtons.layout(layout -> layout.widthPercent(100).minWidth(0)
                .justifyContent(YogaJustify.CENTER));
        quantityButtons.addChild(button(Component.translatable("buildshop.ui.qty.confirm"), event -> confirmQuantity(), 110));
        quantityButtons.addChild(button(Component.translatable("buildshop.ui.qty.cancel"), event -> hideQuantityDialog(), 110));
        quantityOverlay.addChild(quantityButtons);
        root.addChild(quantityOverlay);
        return root;
    }

    private void renderBalance() {
        if (balanceLabel == null) return;
        ShopClientModel model = state.model();
        String currency = model.defaultCurrency();
        String currencyText = model.currencyAvailable(currency)
                ? model.currencyName(currency) + " · " + model.balance(currency)
                : model.currencyLabel(currency) + " · "
                + Component.translatable("buildshop.payment.unknown_currency").getString();
        balanceLabel.setText(Component.translatable("buildshop.ui.balance.label", currencyText));
    }

    private void renderCategories() {
        if (categoriesView == null) return;
        categoriesView.clearAllScrollViewChildren();
        categoriesView.addScrollViewChild(categoryButton(Category.ALL_ID, Component.translatable("buildshop.ui.category.all").getString()));
        ShopClientModel model = state.model();
        for (CategoryDto category : model.categories()) {
            boolean empty = model.products().stream().noneMatch(product -> product.categories().contains(category.id()));
            if (model.hideEmptyCategories() && empty) continue;
            categoriesView.addScrollViewChild(categoryButton(category.id(), category.name()));
        }
    }

    private UIElement categoryButton(String id, String text) {
        Button button = new Button().setText(text);
        button.textStyle(style -> style.fontSize(scale(8)).textShadow(false));
        button.layout(layout -> layout.height(scale(28)).widthPercent(100)
                .minWidth(0).marginBottom(scale(4)));
        button.setOnClick(event -> {
            selectedCategory = id;
            rebuildProducts();
        });
        return button;
    }

    private void rebuildProducts() {
        if (productsView == null) return;
        List<ProductDto> filtered = state.filteredProducts(selectedCategory, searchText, sortMode);
        List<List<ProductDto>> rows = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i += PRODUCTS_PER_ROW) {
            rows.add(List.copyOf(filtered.subList(i, Math.min(i + PRODUCTS_PER_ROW, filtered.size()))));
        }
        if (rows.isEmpty()) rows.add(List.of());
        productsView.setItems(rows).setItemUIProvider(this::productRow);
        if (countLabel != null) {
            countLabel.setText(Component.translatable("buildshop.ui.count", filtered.size(), state.model().products().size()));
        }
    }

    private UIElement productRow(List<ProductDto> products) {
        UIElement row = row(scale(4), scale(74));
        row.layout(layout -> layout.widthPercent(100).minWidth(0));
        for (ProductDto product : products) row.addChild(productCard(product));
        for (int i = products.size(); i < PRODUCTS_PER_ROW; i++) {
            UIElement spacer = new UIElement().layout(layout -> layout.flex(1));
            row.addChild(spacer);
        }
        if (products.isEmpty()) row.addChild(label(Component.translatable("buildshop.ui.empty")));
        return row;
    }

    private UIElement productCard(ProductDto product) {
        String tooltipDescription = product.description() == null ? "" : product.description();
        if (!state.deliveryAvailable(product)) {
            tooltipDescription = Component.translatable("buildshop.ui.item_unavailable").getString();
        }
        Component stockTooltip = product.stockMode() == com.tanrunn.buildshop.core.StockMode.INFINITE
                ? Component.translatable("buildshop.ui.stock.infinite")
                : Component.translatable("buildshop.ui.stock.remaining", Math.max(0, product.stockRemaining()));
        List<Component> tooltipLines = new ArrayList<>();
        if (!tooltipDescription.isBlank()) {
            tooltipLines.add(Component.literal(tooltipDescription));
        }
        // Stock remains available to the player without spending any of the compact card's width.
        tooltipLines.add(stockTooltip);
        com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture normalBackground = product.enabled()
                && state.model().currencyAvailable(product.currency()) ? Sprites.BORDER1 : Sprites.BORDER1_DARK;
        UIElement card = new UIElement()
                .layout(layout -> layout.flex(1).height(scale(72)).minWidth(0)
                        .flexDirection(YogaFlexDirection.COLUMN).alignItems(YogaAlign.CENTER)
                        // Reserve a full 10px at the top and bottom: the icon and price should
                        // read as deliberately placed content, not as card borders.
                        .paddingHorizontal(scale(3)).paddingVertical(scale(10)).gapAll(scale(2)))
                .style(style -> {
                    style.background(normalBackground);
                    style.tooltips(tooltipLines.toArray(Component[]::new));
                });
        card.addEventListener(UIEvents.MOUSE_ENTER,
                event -> card.style(style -> style.background(Sprites.BORDER)));
        card.addEventListener(UIEvents.MOUSE_LEAVE,
                event -> card.style(style -> style.background(normalBackground)));
        UIElement iconElement = product.isEntityProduct() ? entityModelIcon(product) : null;
        if (iconElement == null && !product.isEntityProduct()) {
            ItemStack icon = ClientItemStackResolver.resolve(product);
            if (!icon.isEmpty()) {
                iconElement = new UIElement()
                        .layout(layout -> layout.width(scale(24)).height(scale(24)))
                        .style(style -> style.background(new ItemStackTexture(icon)));
            }
        }
        if (iconElement != null) card.addChild(iconElement);
        Label name = label(product.displayName());
        name.layout(layout -> layout.widthPercent(100).height(scale(14)));
        centerCardText(name, scale(9));
        card.addChild(name);
        boolean currencyAvailable = state.model().currencyAvailable(product.currency());
        // formattedPrice is already formatted server-side as "15 铜币". Appending the currency
        // label here used to produce the duplicate "15 铜币 · 铜币" shown on every card.
        String priceText = product.formattedPrice();
        String currencyName = state.model().currencyName(product.currency());
        boolean copperCurrency = currencyAvailable && "铜币".equals(currencyName)
                && priceText.endsWith(currencyName);
        if (copperCurrency) {
            priceText = priceText.substring(0, priceText.length() - currencyName.length()).trim() + " 🪙";
        }
        if (!currencyAvailable) {
            priceText += " · " + Component.translatable("buildshop.payment.unknown_currency").getString()
                    + " (" + state.model().compactCurrencyId(product.currency()) + ")";
        }
        Label price = label(priceText);
        price.layout(layout -> layout.widthPercent(100).height(scale(10)));
        centerCardText(price, scale(8));
        if (copperCurrency) {
            price.textStyle(style -> style.textColor(0xFFFFC107));
        }
        card.addChild(price);
        card.addEventListener(UIEvents.MOUSE_DOWN, event -> onProductMouseDown(product, event));
        return card;
    }

    /**
     * Render entity products as their actual client entity model. This is deliberately kept in
     * the LDLib2 controller: AUI continues to use its existing spawn-egg/item icon behaviour.
     */
    private UIElement entityModelIcon(ProductDto product) {
        ResourceLocation entityId = ResourceLocation.tryParse(product.entityId());
        Minecraft minecraft = Minecraft.getInstance();
        if (entityId == null || minecraft.level == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) {
            return null;
        }

        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId);
        try {
            Scene scene = new Scene().createScene(minecraft.level, false,
                    Size.of(Math.max(1, Math.round(scale(24))), Math.max(1, Math.round(scale(24)))));
            Entity entity = entityType.create(scene.getDummyWorld());
            if (entity == null) return null;

            entity.setPos(0, 0, 0);
            entity.setYRot(ENTITY_MODEL_YAW);
            entity.yRotO = ENTITY_MODEL_YAW;
            if (entity instanceof LivingEntity living) {
                living.setYBodyRot(ENTITY_MODEL_YAW);
                living.yBodyRotO = ENTITY_MODEL_YAW;
                living.setYHeadRot(ENTITY_MODEL_YAW);
                living.yHeadRotO = ENTITY_MODEL_YAW;
            }
            entity.setNoGravity(true);
            scene.getDummyWorld().addEntity(entity);

            float modelSize = Math.max(entity.getBbHeight(), entity.getBbWidth());
            float cameraDistance = Math.max(1.4f, modelSize * 2.2f);
            scene.useOrtho(false)
                    .setCenter(new Vector3f(0, entity.getBbHeight() * 0.5f, 0))
                    .setZoom(cameraDistance)
                    .setCameraYawAndPitch(-135, 15)
                    .setDraggable(false)
                    .setScalable(false)
                    .setIntractable(false)
                    .setTickWorld(false);
            return scene.layout(layout -> layout.width(scale(24)).height(scale(24)));
        } catch (RuntimeException ignored) {
            // A malformed or client-only entity must not prevent the rest of the shop opening.
            return null;
        }
    }

    private void onProductMouseDown(ProductDto product, UIEvent event) {
        if (!state.model().currencyAvailable(product.currency())) {
            showStatus(Component.translatable("buildshop.payment.unknown_currency").getString());
            return;
        }
        if (event.button == 1) {
            showQuantityDialog(product);
            return;
        }
        if (event.button != 0) return;
        PurchaseMode mode = event.isCtrlDown() ? PurchaseMode.MAX
                : event.isShiftDown() ? PurchaseMode.BULK : PurchaseMode.SINGLE;
        state.requestPurchase(product.id(), mode, 0);
    }

    private void showQuantityDialog(ProductDto product) {
        if (quantityOverlay == null || quantityField == null) return;
        quantityProductId = product.id();
        quantityField.setText("1");
        quantityOverlay.setDisplay(true);
        quantityOverlay.setActive(true);
    }

    private void hideQuantityDialog() {
        quantityProductId = null;
        if (quantityOverlay != null) {
            quantityOverlay.setActive(false);
            quantityOverlay.setDisplay(false);
        }
    }

    private void confirmQuantity() {
        if (quantityProductId == null || quantityField == null) return;
        int quantity;
        try {
            quantity = Integer.parseInt(quantityField.getValue().trim());
        } catch (RuntimeException error) {
            showStatus(Component.translatable("buildshop.ui.qty.invalid").getString());
            return;
        }
        if (quantity <= 0) {
            showStatus(Component.translatable("buildshop.ui.qty.invalid").getString());
            return;
        }
        String productId = quantityProductId;
        hideQuantityDialog();
        state.requestPurchase(productId, PurchaseMode.CUSTOM, quantity);
    }

    private void setSort(ClientShopState.SortMode sort) {
        sortMode = sort;
        rebuildProducts();
    }

    private void requestSync() {
        showStatus(Component.translatable("buildshop.ui.syncing").getString());
        PacketDistributor.sendToServer(new RequestSyncPayload());
    }

    private void showStatus(String text) {
        if (statusLabel != null) statusLabel.setText(text == null ? "" : text);
    }

    private static UIElement row(float gap, float height) {
        UIElement element = new UIElement().layout(layout -> {
            layout.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(gap);
            if (height > 0) layout.height(height);
        });
        return element;
    }

    private static Label label(Component text) {
        Label label = new Label();
        label.setText(text);
        label.textStyle(style -> style.fontSize(scale(9)).textShadow(false));
        return label;
    }

    private static Label label(String text) {
        return label(Component.literal(text == null ? "" : text));
    }

    private static void centerCardText(Label label, float fontSize) {
        label.textStyle(style -> style.textWrap(TextWrap.HIDE).fontSize(fontSize).textShadow(false)
                .textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
    }

    private static Button button(Component text, com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener listener, float width) {
        Button button = new Button().setText(text);
        button.textStyle(style -> style.fontSize(scale(9)).textShadow(false));
        button.layout(layout -> layout.width(scale(width)).height(scale(26)));
        button.setOnClick(listener);
        return button;
    }

    private static float scale(float value) {
        return LdLib2UiMetrics.scale(value);
    }

    private static Size fitToScreen(Size screen) {
        return LdLib2UiMetrics.fitToScreen(screen);
    }
}
