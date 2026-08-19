package com.tanrunn.buildshop.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.api.BuildingShopApi;
import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.ProductCatalog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.neoforge.resource.ResourcePackLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** /buildingshop 命令。 */
public final class ShopCommands {

    private ShopCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("buildingshop")
                .executes(ctx -> openShop(ctx))
                .then(Commands.literal("open")
                        .executes(ctx -> openShop(ctx)))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reload(ctx)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx)))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("balance")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> balance(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> balance(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("give")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> give(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(Commands.literal("stock")
                        .requires(source -> source.hasPermission(2))
                        // 字面节点必须放在参数节点之前：Brigadier 的 word() 参数是贪婪的，
                        // 放在 set/add/restock 之前会把字面量吞掉导致整条命令解析失败。
                        .then(Commands.literal("set")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(0))
                                                .executes(ctx -> setStock(ctx, StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "quantity"))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                .executes(ctx -> addStock(ctx, StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "quantity"))))))
                        .then(Commands.literal("restock")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> restock(ctx, StringArgumentType.getString(ctx, "id")))))));
    }

    private static int openShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!Config.ENABLED.get() && !ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.translatable("buildshop.command.open.disabled"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return BuildingShopApi.openPanel(player) ? 1 : 0;
    }

    /**
     * 与原版 /reload 等价的安全数据包重载：
     * <ul>
     *   <li>保留当前已选择的数据包（{@code packRepository.getSelectedIds()}）；</li>
     *   <li>重新扫描数据包目录，把新加入且未在禁用列表中的数据包纳入本次重载；</li>
     *   <li>不卸载服务器已有的外部数据包（绝不传空集合给 {@code reloadResources}）。</li>
     * </ul>
     * 异步 reload 成功后再发送成功消息；失败时发送失败消息并记录异常。
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PackRepository packRepository = source.getServer().getPackRepository();
        WorldData worldData = source.getServer().getWorldData();

        List<String> packs = new ArrayList<>(packRepository.getSelectedIds());
        packRepository.reload();
        DataPackConfig dataPackConfig = worldData.getDataConfiguration().dataPacks();
        for (String id : packRepository.getAvailableIds()) {
            if (!dataPackConfig.getDisabled().contains(id) && !packs.contains(id)) {
                packs.add(id);
            }
        }
        // 与 NeoForge 原版 /reload 等价：新发现的数据包按默认位置/优先级重排，
        // 避免内置/Mod 数据包出现在错误优先级。
        ResourcePackLoader.reorderNewlyDiscoveredPacks(packs, new ArrayList<>(packRepository.getSelectedIds()), packRepository);

        CompletableFuture<Void> future = source.getServer().reloadResources(packs);
        future.whenComplete((unused, throwable) -> source.getServer().execute(() -> {
            if (throwable != null) {
                BuildShopMod.LOGGER.error("[Shop] datapack reload failed", throwable);
                source.sendFailure(Component.translatable("buildshop.command.reload.failed"));
            } else {
                source.sendSuccess(() -> Component.translatable("buildshop.command.reload.done"), true);
            }
        }));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ProductCatalog catalog = ShopServer.INSTANCE.catalog();
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.list.header", catalog.productCount()), false);
        for (Product product : catalog.enabledProducts()) {
            ctx.getSource().sendSuccess(() -> Component.literal(" - ")
                    .append(Component.literal(product.effectiveName()))
                    .append(Component.literal(" [" + product.id() + "]  ")
                            .withStyle(style -> style.withColor(0x9ca3af)))
                    .append(Component.literal(ShopServer.INSTANCE.formatPrice(product.currency(), product.unitPrice()))), false);
        }
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String id) {
        ProductCatalog catalog = ShopServer.INSTANCE.catalog();
        Product product = catalog.product(id).orElse(null);
        if (product == null) {
            ctx.getSource().sendFailure(Component.translatable("buildshop.command.info.not_found", id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(product.effectiveName() + " [" + product.id() + "]"), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.info.item", product.itemId()), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.info.price",
                ShopServer.INSTANCE.formatPrice(product.currency(), product.unitPrice()),
                product.currency()), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.info.bulk", product.effectiveBulkSize()), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.info.stock",
                product.stockMode().name(),
                ShopServer.INSTANCE.stock().remaining(product.id())), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.info.categories",
                String.join(", ", product.categories())), false);
        if (product.description() != null && !product.description().isBlank()) {
            ctx.getSource().sendSuccess(() -> Component.literal(product.description()), false);
        }
        return 1;
    }

    private static int balance(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        String currency = Config.DEFAULT_CURRENCY.get();
        String formatted = ShopServer.INSTANCE.formatBalance(target, currency);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.balance", target.getDisplayName(), formatted), false);
        return 1;
    }

    /** 发放虚拟金币（不是商品物品）：{@code /buildingshop give <玩家> <数量>}。 */
    private static int give(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int amount) {
        ShopSavedData data = ShopServer.INSTANCE.dataOf(target);
        long current = data.virtualBalance(target.getUUID());
        data.setVirtualBalance(target.getUUID(), current + amount);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.give.done",
                target.getDisplayName(), amount, ShopServer.INSTANCE.formatPrice(Config.DEFAULT_CURRENCY.get(), current + amount)), true);
        ShopServer.INSTANCE.syncTo(target);
        return 1;
    }

    // ------------------------------------------------------------------ stock

    /** 校验商品是否存在且为有限库存；返回 null 表示可用，否则为错误消息 key。 */
    private static String stockTarget(CommandContext<CommandSourceStack> ctx, String id, Product[] holder) {
        ProductCatalog catalog = ShopServer.INSTANCE.catalog();
        Product product = catalog.product(id).orElse(null);
        if (product == null) {
            return "buildshop.command.stock.not_found";
        }
        if (product.stockMode() != com.tanrunn.buildshop.core.StockMode.FINITE) {
            return "buildshop.command.stock.infinite_error";
        }
        holder[0] = product;
        return null;
    }

    private static void syncAll(CommandContext<CommandSourceStack> ctx) {
        ShopServer.INSTANCE.syncToAll(ctx.getSource().getServer().overworld());
    }

    private static int setStock(CommandContext<CommandSourceStack> ctx, String id, int quantity) {
        Product[] holder = new Product[1];
        String error = stockTarget(ctx, id, holder);
        if (error != null) {
            ctx.getSource().sendFailure(Component.translatable(error, id));
            return 0;
        }
        ShopServer.INSTANCE.updateStock(ctx.getSource().getLevel(), id, quantity);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.stock.set.done", id, quantity), true);
        syncAll(ctx);
        return 1;
    }

    private static int addStock(CommandContext<CommandSourceStack> ctx, String id, int quantity) {
        Product[] holder = new Product[1];
        String error = stockTarget(ctx, id, holder);
        if (error != null) {
            ctx.getSource().sendFailure(Component.translatable(error, id));
            return 0;
        }
        ShopSavedData data = ShopServer.INSTANCE.dataOf(ctx.getSource().getLevel());
        int current = Math.max(0, data.stockOf(id));
        ShopServer.INSTANCE.updateStock(ctx.getSource().getLevel(), id, current + quantity);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.stock.add.done", id, quantity, current + quantity), true);
        syncAll(ctx);
        return 1;
    }

    private static int restock(CommandContext<CommandSourceStack> ctx, String id) {
        Product[] holder = new Product[1];
        String error = stockTarget(ctx, id, holder);
        if (error != null) {
            ctx.getSource().sendFailure(Component.translatable(error, id));
            return 0;
        }
        ShopServer.INSTANCE.updateStock(ctx.getSource().getLevel(), id, holder[0].stockQuantity());
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.stock.restock.done", id, holder[0].stockQuantity()), true);
        syncAll(ctx);
        return 1;
    }
}
