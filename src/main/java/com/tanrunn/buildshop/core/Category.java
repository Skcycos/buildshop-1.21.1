package com.tanrunn.buildshop.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 商店分类（纯数据，无 Minecraft 依赖）。 */
public final class Category {
    public static final String ALL_ID = "__all__";

    private final String id;
    private final String name;
    private final String iconItemId;
    private final int sort;
    private final boolean enabled;

    public Category(String id, String name, String iconItemId, int sort, boolean enabled) {
        this.id = id;
        this.name = name == null || name.isBlank() ? id : name;
        this.iconItemId = iconItemId;
        this.sort = sort;
        this.enabled = enabled;
    }

    /** 分类 ID 优先取 JSON {@code id} 字段，缺省回退为资源键。 */
    public static Category fromJson(String resourceKey, JsonObject json) {
        String id = optString(json, "id", null);
        if (id == null || id.isBlank()) {
            id = resourceKey;
        }
        String name = optString(json, "name", null);
        String icon = optString(json, "icon", null);
        int sort = optInt(json, "sort", 0);
        // enabled 非布尔时按未配置（启用）处理，不抛异常终止加载。
        boolean enabled = !json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                || json.get("enabled").getAsBoolean();
        return new Category(id, name, icon, sort, enabled);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** 分类图标物品 ID，可为 null（客户端显示占位）。 */
    public String iconItemId() {
        return iconItemId;
    }

    public int sort() {
        return sort;
    }

    public boolean enabled() {
        return enabled;
    }

    private static String optString(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
