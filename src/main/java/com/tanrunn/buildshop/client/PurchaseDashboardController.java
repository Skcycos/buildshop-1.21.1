package com.tanrunn.buildshop.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.sighs.apricityui.spi.AuiServices;
import com.sighs.apricityui.task.FrameTaskScheduler;
import com.tanrunn.buildshop.BuildShopMod;
import net.minecraft.client.Minecraft;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 建材商店购买看板：读取客户端本地历史，用 AUI DOM 绘制统计图。 */
public final class PurchaseDashboardController {
    public static final PurchaseDashboardController INSTANCE = new PurchaseDashboardController();
    public static final String TEMPLATE_PATH = "buildingshop/screens/purchase_dashboard.html";

    private final NumberFormat amountFormat = NumberFormat.getIntegerInstance(Locale.ROOT);
    private Document document;
    private long generation;
    private List<ClientPurchaseRecord> records = List.of();
    private long currentGameDay;

    private PurchaseDashboardController() {
    }

    public void open() {
        document = null;
        reloadLocalData();
        AuiServices.client().openScreen(TEMPLATE_PATH);
        if (Minecraft.getInstance().screen instanceof ApricityScreen screen) {
            bind(screen.getLinkedDocument());
        }
    }

    private void reloadLocalData() {
        records = ClientPurchaseHistory.INSTANCE.records();
        long gameTime = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
        currentGameDay = Math.max(0, gameTime / 24_000L);
    }

    private void bind(Document linked) {
        document = linked;
        if (linked == null) {
            BuildShopMod.LOGGER.error("[ShopUI] dashboard template missing: {}", TEMPLATE_PATH);
            return;
        }
        generation = linked.getRefreshGeneration();
        rebind();
        FrameTaskScheduler.scheduleAfterFrames(1, this::watchdogTick);
    }

    private boolean watchdogTick(long deadlineNs) {
        ensureCurrentDocument();
        if (document != null && !document.isDisposed()) {
            FrameTaskScheduler.scheduleAfterFrames(1, this::watchdogTick);
        }
        return true;
    }

    private void ensureCurrentDocument() {
        Document doc = document;
        if (doc == null || doc.isDisposed()) return;
        long current = doc.getRefreshGeneration();
        if (current == generation) return;
        generation = current;
        rebind();
        render();
    }

    private void rebind() {
        Document doc = document;
        if (doc == null) return;
        Element back = doc.getElementById("dashboard-back");
        Element refresh = doc.getElementById("dashboard-refresh");
        if (back != null) back.addEventListener("click", event -> ShopScreenController.INSTANCE.reopen());
        if (refresh != null) refresh.addEventListener("click", event -> {
            reloadLocalData();
            render();
        });
        render();
    }

    private void render() {
        Document doc = document;
        if (doc == null || doc.isDisposed()) return;
        long totalSpent = records.stream().mapToLong(ClientPurchaseRecord::totalPrice).sum();
        long totalQuantity = records.stream().mapToLong(ClientPurchaseRecord::quantity).sum();
        Map<String, Long> byCategory = categoryTotals();
        String topCategory = byCategory.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("暂无");

        setText(doc, "dashboard-total", formatAmount(totalSpent));
        setText(doc, "dashboard-orders", formatAmount(records.size()));
        setText(doc, "dashboard-quantity", formatAmount(totalQuantity));
        setText(doc, "dashboard-top-category", topCategory);
        renderDailyChart(doc);
        renderCategoryChart(doc, byCategory);
        renderRecords(doc);
    }

    private void renderDailyChart(Document doc) {
        Element chart = doc.getElementById("daily-chart");
        if (chart == null) return;
        chart.clearChildren();
        Map<Long, Long> totals = new LinkedHashMap<>();
        long firstDay = Math.max(0, currentGameDay - 6);
        for (long day = firstDay; day <= currentGameDay; day++) totals.put(day, 0L);
        for (ClientPurchaseRecord record : records) {
            if (totals.containsKey(record.gameDay())) {
                totals.put(record.gameDay(), totals.get(record.gameDay()) + Math.max(0, record.totalPrice()));
            }
        }
        long max = totals.values().stream().mapToLong(Long::longValue).max().orElse(0);
        for (Map.Entry<Long, Long> entry : totals.entrySet()) {
            Element column = doc.createElement("div");
            column.setAttribute("class", "day-column");
            Element value = doc.createElement("span");
            value.setAttribute("class", "day-value");
            value.setTextContent(entry.getValue() <= 0 ? "" : formatAmount(entry.getValue()));
            column.appendChild(value);
            Element track = doc.createElement("div");
            track.setAttribute("class", "day-track");
            Element fill = doc.createElement("div");
            fill.setAttribute("class", "day-fill");
            double percent = max <= 0 ? 0 : entry.getValue() * 100.0 / max;
            fill.setInlineStyleProperty("height", Math.max(3, percent) + "%");
            if (entry.getValue() <= 0) fill.setInlineStyleProperty("opacity", "0.25");
            track.appendChild(fill);
            column.appendChild(track);
            Element label = doc.createElement("span");
            label.setAttribute("class", "day-label");
            label.setTextContent("第 " + entry.getKey() + " 天");
            column.appendChild(label);
            chart.appendChild(column);
        }
    }

    private void renderCategoryChart(Document doc, Map<String, Long> totals) {
        Element chart = doc.getElementById("category-chart");
        if (chart == null) return;
        chart.clearChildren();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        if (entries.isEmpty()) {
            appendEmpty(doc, chart, "还没有足够的购买数据");
            return;
        }
        long max = entries.get(0).getValue();
        for (Map.Entry<String, Long> entry : entries.stream().limit(6).toList()) {
            Element row = doc.createElement("div");
            row.setAttribute("class", "category-row");
            Element name = doc.createElement("span");
            name.setAttribute("class", "category-name");
            name.setTextContent(entry.getKey());
            row.appendChild(name);
            Element track = doc.createElement("div");
            track.setAttribute("class", "category-track");
            Element fill = doc.createElement("div");
            fill.setAttribute("class", "category-fill");
            fill.setInlineStyleProperty("width", (max <= 0 ? 0 : entry.getValue() * 100.0 / max) + "%");
            track.appendChild(fill);
            row.appendChild(track);
            Element amount = doc.createElement("span");
            amount.setAttribute("class", "category-amount");
            amount.setTextContent(formatAmount(entry.getValue()));
            row.appendChild(amount);
            chart.appendChild(row);
        }
    }

    private void renderRecords(Document doc) {
        Element list = doc.getElementById("purchase-records");
        if (list == null) return;
        list.clearChildren();
        if (records.isEmpty()) {
            appendEmpty(doc, list, "暂时没有成功购买记录");
            return;
        }
        for (ClientPurchaseRecord record : records) {
            Element row = doc.createElement("div");
            row.setAttribute("class", "record-row");
            Element marker = doc.createElement("div");
            marker.setAttribute("class", "record-marker");
            marker.setTextContent("购");
            row.appendChild(marker);
            Element copy = doc.createElement("div");
            copy.setAttribute("class", "record-copy");
            Element title = doc.createElement("strong");
            title.setTextContent(record.productName());
            copy.appendChild(title);
            Element meta = doc.createElement("span");
            meta.setTextContent("第 " + record.gameDay() + " 天 · " + clock(record.gameTime())
                    + " · " + record.categoryName());
            copy.appendChild(meta);
            row.appendChild(copy);
            Element amount = doc.createElement("div");
            amount.setAttribute("class", "record-amount");
            Element quantity = doc.createElement("strong");
            quantity.setTextContent("×" + record.quantity());
            amount.appendChild(quantity);
            Element price = doc.createElement("span");
            price.setTextContent("- " + formatAmount(record.totalPrice()) + " " + record.currencyName());
            amount.appendChild(price);
            row.appendChild(amount);
            list.appendChild(row);
        }
    }

    private Map<String, Long> categoryTotals() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (ClientPurchaseRecord record : records) {
            result.merge(record.categoryName(), Math.max(0, record.totalPrice()), Long::sum);
        }
        return result;
    }

    private String clock(long gameTime) {
        long tick = Math.floorMod(gameTime, 24_000L);
        int minute = (int) ((tick / 50L + 6 * 60L) % (24 * 60L));
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private String formatAmount(long value) {
        return amountFormat.format(Math.max(0, value));
    }

    private static void appendEmpty(Document doc, Element parent, String text) {
        Element empty = doc.createElement("div");
        empty.setAttribute("class", "dashboard-empty");
        empty.setTextContent(text);
        parent.appendChild(empty);
    }

    private static void setText(Document doc, String id, String value) {
        Element element = doc.getElementById(id);
        if (element != null) element.setTextContent(value == null ? "—" : value);
    }
}
