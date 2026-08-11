package com.tanrunn.buildshop.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tanrunn.buildshop.core.Product;
import com.tanrunn.buildshop.core.ProductCatalog;
import com.tanrunn.buildshop.network.BuildShopNetwork.OpenShopPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;

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
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.literal("set")
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(0))
                                                .executes(ctx -> setStock(ctx, StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "quantity")))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                                .executes(ctx -> addStock(ctx, StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "quantity")))))
                                .then(Commands.literal("restock")
                                        .executes(ctx -> restock(ctx, StringArgumentType.getString(ctx, "id")))))));
    }

    private static int openShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PacketDistributor.sendToPlayer(player, new OpenShopPayload());
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getServer().reloadResources(Collections.emptyList());
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.reload.done"), true);
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
        String currency = com.tanrunn.buildshop.Config.DEFAULT_CURRENCY.get();
        String formatted = ShopServer.INSTANCE.formatBalance(target, currency);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.balance", target.getDisplayName(), formatted), false);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int amount) {
        ShopSavedData data = ShopServer.INSTANCE.dataOf(target);
        long current = data.virtualBalance(target.getUUID());
        data.setVirtualBalance(target.getUUID(), current + amount);
        ctx.getSource().sendSuccess(() -> Component.translatable("buildshop.command.give.done",
                target.getDisplayName(), amount, ShopServer.INSTANCE.formatPrice(com.tanrunn.buildshop.Config.DEFAULT_CURRENCY.get(), current + amount)), true);
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
