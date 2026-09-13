package com.tanrunn.buildshop.client.ldlib2;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.math.Size;
import com.tanrunn.buildshop.client.ClientPurchaseHistory;
import com.tanrunn.buildshop.client.ClientPurchaseRecord;
import com.tanrunn.buildshop.client.ClientShopState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native LDLib2 purchase dashboard with summary cards, seven-day bars, category bars and records. */
public final class LdLib2PurchaseDashboardController {
    private final ClientShopState state;
    private final Runnable back;
    private final NumberFormat amountFormat = NumberFormat.getIntegerInstance(Locale.ROOT);
    private ModularUIScreen screen;
    private Label totalLabel;
    private Label ordersLabel;
    private Label quantityLabel;
    private Label topCategoryLabel;
    private UIElement dailyChart;
    private UIElement categoryChart;
    private VirtualScrollerView<ClientPurchaseRecord> recordsView;

    public LdLib2PurchaseDashboardController(ClientShopState state, Runnable back) {
        this.state = state;
        this.back = back;
    }

    public void open() {
        UIElement root = buildRoot();
        screen = new ModularUIScreen(ModularUI.of(UI.of(root, LdLib2PurchaseDashboardController::fitToScreen)),
                Component.translatable("buildshop.ui.ldlib2.dashboard"));
        Minecraft.getInstance().setScreen(screen);
        refreshFromState();
    }

    public void refreshFromState() {
        if (screen == null || Minecraft.getInstance().screen != screen) return;
        List<ClientPurchaseRecord> records = ClientPurchaseHistory.INSTANCE.records();
        long total = sumPrices(records);
        long quantity = sumQuantities(records);
        Map<String, Long> byCategory = categoryTotals(records);
        String top = byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("暂无");
        totalLabel.setText(format(total));
        ordersLabel.setText(format(records.size()));
        quantityLabel.setText(format(quantity));
        topCategoryLabel.setText(top);
        renderDailyChart(records);
        renderCategoryChart(byCategory);
        recordsView.setItems(records).setItemUIProvider(this::recordRow);
    }

    private UIElement buildRoot() {
        UIElement root = new UIElement()
                .layout(layout -> layout.width(scale(820)).height(scale(560)).flexDirection(YogaFlexDirection.COLUMN)
                        .paddingAll(scale(10)).gapAll(scale(6)))
                .style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.BORDER));
        UIElement header = row(scale(6), scale(34));
        Label title = label(Component.translatable("buildshop.ui.ldlib2.dashboard"));
        title.layout(layout -> layout.flex(1));
        header.addChild(title);
        Button refresh = new Button().setText(Component.translatable("buildshop.ui.refresh"));
        refresh.textStyle(style -> style.fontSize(scale(9)).textShadow(false));
        refresh.layout(layout -> layout.width(scale(80)).height(scale(26)));
        refresh.setOnClick(event -> refreshFromState());
        header.addChild(refresh);
        Button backButton = new Button().setText(Component.translatable("buildshop.ui.back"));
        backButton.textStyle(style -> style.fontSize(scale(9)).textShadow(false));
        backButton.layout(layout -> layout.width(scale(80)).height(scale(26)));
        backButton.setOnClick(event -> back.run());
        header.addChild(backButton);
        root.addChild(header);

        UIElement stats = row(scale(6), scale(62));
        stats.layout(layout -> layout.widthPercent(100).minWidth(0));
        totalLabel = addStat(stats, "buildshop.ui.dashboard.total", "—");
        ordersLabel = addStat(stats, "buildshop.ui.dashboard.orders", "—");
        quantityLabel = addStat(stats, "buildshop.ui.dashboard.quantity", "—");
        topCategoryLabel = addStat(stats, "buildshop.ui.dashboard.top_category", "—");
        root.addChild(stats);

        UIElement charts = row(scale(8), scale(140));
        charts.layout(layout -> layout.widthPercent(100).minWidth(0).minHeight(0));
        dailyChart = chartPanel("buildshop.ui.dashboard.daily");
        dailyChart.layout(layout -> layout.flex(1).minWidth(0).minHeight(0));
        charts.addChild(dailyChart);
        categoryChart = chartPanel("buildshop.ui.dashboard.categories");
        categoryChart.layout(layout -> layout.flex(1).minWidth(0).minHeight(0));
        charts.addChild(categoryChart);
        root.addChild(charts);

        Label recordsTitle = compactLabel(Component.translatable("buildshop.ui.dashboard.records"), scale(8), TextWrap.HIDE);
        recordsTitle.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(18))
                .paddingHorizontal(scale(3)));
        root.addChild(recordsTitle);
        recordsView = new VirtualScrollerView<>();
        recordsView.layout(layout -> layout.flex(1).widthPercent(100).minWidth(0).minHeight(0));
        recordsView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        recordsView.virtualScrollerViewStyle(style -> style.estimatedItemHeight(scale(30)).overscanPixels(scale(60)));
        root.addChild(recordsView);
        return root;
    }

    private Label addStat(UIElement parent, String key, String initial) {
        UIElement card = new UIElement().layout(layout -> layout.flex(1).height(scale(54)).minWidth(0)
                        .paddingAll(scale(8)).gapAll(scale(2)))
                .style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.BORDER1));
        Label title = compactLabel(Component.translatable(key), scale(8), TextWrap.HIDE);
        title.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(14)));
        card.addChild(title);
        Label value = compactLabel(initial, scale(10), TextWrap.HIDE);
        value.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(16)));
        card.addChild(value);
        parent.addChild(card);
        return value;
    }

    private UIElement chartPanel(String titleKey) {
        UIElement panel = new UIElement().layout(layout -> layout.widthPercent(100).minWidth(0).minHeight(0)
                        .paddingAll(scale(8)).gapAll(scale(4)))
                .style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.BORDER1));
        Label title = compactLabel(Component.translatable(titleKey), scale(8), TextWrap.HIDE);
        title.layout(layout -> layout.widthPercent(100).minWidth(0).height(scale(16))
                .paddingHorizontal(scale(2)));
        panel.addChild(title);
        return panel;
    }

    private void renderDailyChart(List<ClientPurchaseRecord> records) {
        dailyChart.clearAllChildren();
        dailyChart.addChild(compactLabel(Component.translatable("buildshop.ui.dashboard.daily"), scale(8), TextWrap.HIDE));
        long currentDay = state.model().products().isEmpty() || Minecraft.getInstance().level == null
                ? records.stream().mapToLong(ClientPurchaseRecord::gameDay).max().orElse(0)
                : Minecraft.getInstance().level.getGameTime() / 24_000L;
        currentDay = Math.max(0, currentDay);
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (int offset = 6; offset >= 0; offset--) totals.put(Math.max(0, currentDay - offset), 0L);
        for (ClientPurchaseRecord record : records) {
            if (totals.containsKey(record.gameDay())) {
                totals.put(record.gameDay(), safeAdd(totals.get(record.gameDay()), record.totalPrice()));
            }
        }
        long max = totals.values().stream().mapToLong(Long::longValue).max().orElse(0);
        UIElement bars = row(scale(3), scale(105));
        bars.layout(layout -> layout.widthPercent(100).minWidth(0).minHeight(0).alignItems(YogaAlign.FLEX_END));
        for (Map.Entry<Long, Long> entry : totals.entrySet()) {
            UIElement column = new UIElement().layout(layout -> layout.flex(1).minWidth(0).height(scale(100)).gapAll(scale(2)));
            column.addChild(compactLabel(entry.getValue() == 0 ? "0" : format(entry.getValue()), scale(7), TextWrap.HIDE));
            UIElement track = new UIElement().layout(layout -> layout.flex(1).minWidth(0).widthPercent(100)
                    .positionType(org.appliedenergistics.yoga.YogaPositionType.RELATIVE));
            float barHeight = max == 0 ? scale(2) : Math.max(scale(2), (float) ((double) entry.getValue() * scale(64) / max));
            UIElement fill = new UIElement().layout(layout -> layout.positionType(org.appliedenergistics.yoga.YogaPositionType.ABSOLUTE)
                    .left(0).right(0).bottom(0).height(barHeight))
                    .style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.RECT_SOLID));
            track.addChild(fill);
            column.addChild(track);
            column.addChild(compactLabel("D" + entry.getKey(), scale(7), TextWrap.HIDE));
            bars.addChild(column);
        }
        dailyChart.addChild(bars);
    }

    private void renderCategoryChart(Map<String, Long> totals) {
        categoryChart.clearAllChildren();
        categoryChart.addChild(compactLabel(Component.translatable("buildshop.ui.dashboard.categories"), scale(8), TextWrap.HIDE));
        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        if (entries.isEmpty()) {
            categoryChart.addChild(label(Component.translatable("buildshop.ui.empty")));
            return;
        }
        long max = entries.get(0).getValue();
        for (Map.Entry<String, Long> entry : entries.stream().limit(6).toList()) {
            UIElement line = row(scale(4), scale(19));
            line.layout(layout -> layout.widthPercent(100).minWidth(0)
                    .paddingHorizontal(scale(3)).paddingVertical(scale(1)));
            Label name = compactLabel(entry.getKey(), scale(7), TextWrap.HIDE);
            name.layout(layout -> layout.width(scale(78)).minWidth(0));
            line.addChild(name);
            UIElement track = new UIElement().layout(layout -> layout.flex(1).minWidth(0).height(scale(8)));
            float percentage = max == 0 ? 0 : (float) ((double) entry.getValue() * 100.0 / max);
            UIElement fill = new UIElement().layout(layout -> layout.widthPercent(percentage).height(scale(8)))
                    .style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.RECT_SOLID));
            track.addChild(fill);
            line.addChild(track);
            Label value = compactLabel(format(entry.getValue()), scale(7), TextWrap.HIDE);
            value.layout(layout -> layout.width(scale(42)).minWidth(0));
            value.textStyle(style -> style.textAlignHorizontal(Horizontal.RIGHT));
            line.addChild(value);
            categoryChart.addChild(line);
        }
    }

    private UIElement recordRow(ClientPurchaseRecord record) {
        UIElement row = row(scale(5), scale(26));
        row.layout(layout -> layout.widthPercent(100).minWidth(0)
                .paddingHorizontal(scale(8)).paddingVertical(scale(2)));
        row.style(style -> style.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.BORDER1));
        Label product = compactLabel(record.productName() + " ×" + record.quantity(), scale(8), TextWrap.HIDE);
        product.layout(layout -> layout.flex(1).minWidth(0));
        row.addChild(product);
        Label amount = compactLabel("- " + format(record.totalPrice()) + " " + record.currencyName(), scale(8), TextWrap.HIDE);
        amount.layout(layout -> layout.width(scale(128)).minWidth(0));
        amount.textStyle(style -> style.textAlignHorizontal(Horizontal.RIGHT));
        row.addChild(amount);
        return row;
    }

    private Map<String, Long> categoryTotals(List<ClientPurchaseRecord> records) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (ClientPurchaseRecord record : records) {
            result.merge(record.categoryName(), record.totalPrice(), LdLib2PurchaseDashboardController::safeAdd);
        }
        return result;
    }

    private static long sumPrices(List<ClientPurchaseRecord> records) {
        long total = 0;
        for (ClientPurchaseRecord record : records) total = safeAdd(total, record.totalPrice());
        return total;
    }

    private static long sumQuantities(List<ClientPurchaseRecord> records) {
        long total = 0;
        for (ClientPurchaseRecord record : records) total = safeAdd(total, record.quantity());
        return total;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private String format(long value) {
        return amountFormat.format(Math.max(0, value));
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

    private static Label compactLabel(Component text, float fontSize, TextWrap wrap) {
        Label label = new Label();
        label.setText(text);
        label.textStyle(style -> style.fontSize(fontSize).textShadow(false).textWrap(wrap)
                .textAlignVertical(Vertical.CENTER));
        return label;
    }

    private static Label compactLabel(String text, float fontSize, TextWrap wrap) {
        return compactLabel(Component.literal(text == null ? "" : text), fontSize, wrap);
    }

    private static float scale(float value) {
        return LdLib2UiMetrics.scale(value);
    }

    private static Size fitToScreen(Size screen) {
        return LdLib2UiMetrics.fitToScreen(screen);
    }
}
