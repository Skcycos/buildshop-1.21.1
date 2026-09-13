package com.tanrunn.buildshop;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = COMMON_BUILDER
            .comment("Master switch for the building shop mod")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BUILTIN_EXAMPLE_DATAPACK = COMMON_BUILDER
            .comment(
                    "Enable the built-in example Building Shop datapack.",
                    "Disable this if you provide your own shop data through external datapacks."
            )
            .define("enableBuiltinExampleDatapack", true);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_CURRENCY = COMMON_BUILDER
            .comment("Default currency id shown in the shop header",
                    "Built-in providers: virtual_coins or items:<itemId>; external providers may also be used")
            .define("defaultCurrency", "virtual_coins");

    public static final ModConfigSpec.LongValue VIRTUAL_INITIAL_BALANCE = COMMON_BUILDER
            .comment("Initial balance granted to players on their first login (virtual_coins)")
            .defineInRange("virtualInitialBalance", 1000L, 0L, Long.MAX_VALUE);

    public static final ModConfigSpec.IntValue SERVER_MAX_PER_REQUEST = COMMON_BUILDER
            .comment("Hard cap on how many items a single purchase request may buy (Ctrl+left-click upper bound)")
            .defineInRange("serverMaxPerRequest", 2304, 1, 64 * 64);

    public static final ModConfigSpec.IntValue PURCHASE_COOLDOWN_TICKS = COMMON_BUILDER
            .comment("Minimum ticks between two purchase requests from the same player (rate limiting)")
            .defineInRange("purchaseCooldownTicks", 4, 0, 200);

    public static final ModConfigSpec.BooleanValue HIDE_EMPTY_CATEGORIES = COMMON_BUILDER
            .comment("Hide categories that contain no products in the shop UI")
            .define("hideEmptyCategories", true);

    /**
     * Server-owned UI selection. The value is sent in the BuildShop open-screen payload,
     * so every player uses the backend selected by the owner before a shop screen is created.
     */
    public static final ModConfigSpec.ConfigValue<String> UI_BACKEND = SERVER_BUILDER
            .comment(
                    "UI backend used by the building shop client.",
                    "Allowed values: aui, ldlib2.",
                    "If the selected client library is missing, the client falls back to the other available backend."
            )
            .translation("buildshop.configuration.uiBackend")
            // Do not use defineInList here: NeoForge 21.1.234 passes null while creating a
            // first SERVER config and its generated Collection#contains predicate NPEs.
            .define("uiBackend", "aui", value -> value instanceof String backend
                    && ("aui".equalsIgnoreCase(backend) || "ldlib2".equalsIgnoreCase(backend)));

    static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();
    static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    /** Kept as a source-compatible alias for integrations that referenced Config.SPEC. */
    @Deprecated
    static final ModConfigSpec SPEC = COMMON_SPEC;
}
