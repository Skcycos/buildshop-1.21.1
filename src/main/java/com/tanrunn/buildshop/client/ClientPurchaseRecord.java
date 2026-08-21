package com.tanrunn.buildshop.client;

/** 客户端本地保存的一笔成功购买记录，不参与服务器经济结算。 */
public record ClientPurchaseRecord(
        long gameDay,
        long gameTime,
        String productId,
        String productName,
        String categoryName,
        String currencyName,
        int quantity,
        long totalPrice
) {
    public ClientPurchaseRecord {
        gameDay = Math.max(0, gameDay);
        gameTime = Math.max(0, gameTime);
        productId = limit(productId, 128);
        productName = limit(productName, 128);
        categoryName = limit(categoryName, 64);
        currencyName = limit(currencyName, 64);
        quantity = Math.max(0, quantity);
        totalPrice = Math.max(0, totalPrice);
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return "—";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
