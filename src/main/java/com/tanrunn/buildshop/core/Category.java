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

    public static Category fromJson(String id, JsonObject json) {
        String name = optString(json, "name", null);
        String icon = optString(json, "icon", null);
        int sort = optInt(json, "sort", 0);
        boolean enabled = json.has("enabled") ? json.get("enabled").getAsBoolean() : true;
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
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }
}
