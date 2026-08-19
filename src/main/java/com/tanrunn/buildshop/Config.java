package com.tanrunn.buildshop;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for the building shop mod")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_CURRENCY = BUILDER
            .comment("Default currency id shown in the shop header",
                    "Provided by VirtualCurrencyProvider (virtual_coins) or items:<itemId>")
            .define("defaultCurrency", "virtual_coins");

    public static final ModConfigSpec.LongValue VIRTUAL_INITIAL_BALANCE = BUILDER
            .comment("Initial balance granted to players on their first login (virtual_coins)")
            .defineInRange("virtualInitialBalance", 1000L, 0L, Long.MAX_VALUE);

    public static final ModConfigSpec.IntValue SERVER_MAX_PER_REQUEST = BUILDER
            .comment("Hard cap on how many items a single purchase request may buy (Ctrl+left-click upper bound)")
            .defineInRange("serverMaxPerRequest", 2304, 1, 64 * 64);

    public static final ModConfigSpec.IntValue PURCHASE_COOLDOWN_TICKS = BUILDER
            .comment("Minimum ticks between two purchase requests from the same player (rate limiting)")
            .defineInRange("purchaseCooldownTicks", 4, 0, 200);

    public static final ModConfigSpec.BooleanValue HIDE_EMPTY_CATEGORIES = BUILDER
            .comment("Hide categories that contain no products in the shop UI")
            .define("hideEmptyCategories", true);

    public static final ModConfigSpec.BooleanValue LOAD_BUILTIN_DATAPACK = BUILDER
            .comment("Load the built-in sample datapack bundled with this mod",
                    "When false, the mod's own data/buildshop sample categories/products are ignored,",
                    "so the shop only serves content provided by server-side datapacks (other namespaces).")
            .define("loadBuiltinDatapack", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}
