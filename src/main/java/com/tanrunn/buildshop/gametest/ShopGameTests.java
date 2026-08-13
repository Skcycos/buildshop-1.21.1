package com.tanrunn.buildshop.gametest;

import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.core.PurchaseFailure;
import com.tanrunn.buildshop.core.PurchaseMode;
import com.tanrunn.buildshop.core.PurchaseResult;
import com.tanrunn.buildshop.server.ShopSavedData;
import com.tanrunn.buildshop.server.ShopServer;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 服务器内集成测试（GameTest）。
 *
 * <p>GameTest 框架会并行运行多个测试，而本 Mod 的库存/余额是共享单例状态，
 * 因此把相互有状态依赖的场景合并为一个顺序执行的 {@code fullServerFlow}，
 * 独立测试只做不触碰共享状态的断言。</p>
 *
 * <p>运行：{@code ./gradlew runGameTestServer -PserverOnly}</p>
 */
@GameTestHolder("buildshop")
@SuppressWarnings("removal") // makeMockServerPlayerInLevel 为 NeoForge 标记待移除的 API，仍可正常使用
public class ShopGameTests {

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void fullServerFlow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // ---- 场景 1：有限库存初始值（新世界由启动流程初始化；这里重置为基线，保证可重复运行）----
        // 全新服务器首次启动的库存初始化已由 JUnit StockReconcilerTest 与全新世界 runServer 验证。
        ShopServer.INSTANCE.updateStock(level, "comparator", 500);
        ShopServer.INSTANCE.updateStock(level, "glowstone", 2000);
        ShopServer.INSTANCE.updateStock(level, "sea_lantern", 800);
        assertEquals(helper, 26, ShopServer.INSTANCE.catalog().productCount(), "目录应完整加载 26 个商品");
        assertEquals(helper, 10, ShopServer.INSTANCE.catalog().categoryCount(), "目录应完整加载 10 个分类");
        assertEquals(helper, 500, ShopServer.INSTANCE.stock().remaining("comparator"), "comparator 基线库存应为 500");
        assertEquals(helper, 2000, ShopServer.INSTANCE.stock().remaining("glowstone"), "glowstone 基线库存应为 2000");
        assertEquals(helper, 800, ShopServer.INSTANCE.stock().remaining("sea_lantern"), "sea_lantern 基线库存应为 800");
        assertEquals(helper, -1, ShopServer.INSTANCE.stock().remaining("cobblestone"), "无限库存商品不应有库存条目");

        helper.startSequence()
                // ---- 场景 2：有限库存购买持久化 + 卖完重载不补满 ----
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                    data.setVirtualBalance(player.getUUID(), 10_000L);

                    PurchaseResult result = ShopServer.INSTANCE.purchase(
                            player, "comparator", PurchaseMode.MAX, 0, UUID.randomUUID().toString());
                    assertTrue(helper, result.success(), "应能一次性买完 500 个 comparator: " + result);
                    assertEquals(helper, 500, result.quantity(), "应买完 500 个");
                    assertEquals(helper, 0, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "卖完后运行时库存应为 0（不是 -1）");
                    assertEquals(helper, 0, data.stockOf("comparator"), "卖完后持久化库存应为 0");

                    // 模拟重载/重启：重新应用目录，库存不得被初始值补满。
                    ShopServer.INSTANCE.applyCatalog(ShopServer.INSTANCE.catalog(), level);
                    assertEquals(helper, 0, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "重载后库存仍应为 0");
                    assertEquals(helper, 0, data.stockOf("comparator"), "重载后持久化库存仍应为 0");

                    // 恢复初始库存，供后续场景使用。
                    ShopServer.INSTANCE.updateStock(level, "comparator", 500);
                })

                // ---- 场景 3：enabled=false 时服务端拒绝购买（伪造网络请求同样被拒）----
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                    data.setVirtualBalance(player.getUUID(), 10_000L);
                    long before = data.virtualBalance(player.getUUID());

                    boolean previous = Config.ENABLED.get();
                    try {
                        Config.ENABLED.set(false);
                        PurchaseResult result = ShopServer.INSTANCE.purchase(
                                player, "comparator", PurchaseMode.SINGLE, 0, UUID.randomUUID().toString());
                        assertTrue(helper, !result.success(), "enabled=false 时购买必须被拒绝");
                        assertEquals(helper, PurchaseFailure.SHOP_DISABLED, result.failure(), "失败原因应为商店关闭");
                        assertEquals(helper, before, data.virtualBalance(player.getUUID()), "拒绝时不得扣款");
                        assertEquals(helper, 500, ShopServer.INSTANCE.stock().remaining("comparator"), "拒绝时不得扣库存");
                    } finally {
                        Config.ENABLED.set(previous);
                    }
                })

                // ---- 场景 4：重复 requestId 重放不重复扣款和发货 ----
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                    data.setVirtualBalance(player.getUUID(), 10_000L);
                    String requestId = UUID.randomUUID().toString();

                    PurchaseResult first = ShopServer.INSTANCE.purchase(
                            player, "comparator", PurchaseMode.SINGLE, 0, requestId);
                    PurchaseResult second = ShopServer.INSTANCE.purchase(
                            player, "comparator", PurchaseMode.SINGLE, 0, requestId);
                    assertTrue(helper, first.success(), "首次购买应成功");
                    assertTrue(helper, second.success(), "重放应返回成功结果");
                    assertEquals(helper, 1, second.quantity(), "重放必须返回第一次的结果");
                    assertEquals(helper, 10_000L - 12, data.virtualBalance(player.getUUID()), "只扣一次款");
                    assertEquals(helper, 499, ShopServer.INSTANCE.stock().remaining("comparator"), "只发一次货");
                    ShopServer.INSTANCE.updateStock(level, "comparator", 500);
                })

                // ---- 场景 5：不同物品的半满堆叠不能计算为背包空间 ----
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    for (int i = 0; i < 34; i++) {
                        player.getInventory().setItem(i, new ItemStack(Items.COMPARATOR, 64));
                    }
                    player.getInventory().setItem(34, new ItemStack(Items.COBBLESTONE, 32));
                    ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                    data.setVirtualBalance(player.getUUID(), 100_000L);

                    PurchaseResult result = ShopServer.INSTANCE.purchase(
                            player, "comparator", PurchaseMode.CUSTOM, 80, UUID.randomUUID().toString());
                    assertTrue(helper, !result.success(), "半满的不同物品堆叠不能当作可用空间");
                    assertEquals(helper, PurchaseFailure.INSUFFICIENT_INVENTORY, result.failure(), "失败原因应为背包不足");
                    assertEquals(helper, 100_000L, data.virtualBalance(player.getUUID()), "失败不得扣款");
                    assertEquals(helper, 500, ShopServer.INSTANCE.stock().remaining("comparator"), "失败不得扣库存");
                })

                // ---- 场景 6：同物品不同 Data Components 不能合并 ----
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    for (int i = 0; i < 34; i++) {
                        player.getInventory().setItem(i, new ItemStack(Items.COMPARATOR, 64));
                    }
                    ItemStack named = new ItemStack(Items.COMPARATOR, 32);
                    named.applyComponents(DataComponentPatch.builder()
                            .set(DataComponents.CUSTOM_NAME, Component.literal("特殊比较器"))
                            .build());
                    player.getInventory().setItem(34, named);
                    ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                    data.setVirtualBalance(player.getUUID(), 100_000L);

                    PurchaseResult result = ShopServer.INSTANCE.purchase(
                            player, "comparator", PurchaseMode.CUSTOM, 80, UUID.randomUUID().toString());
                    assertTrue(helper, !result.success(), "不同组件的已有堆叠不能合并，80 个应放不下");
                    assertEquals(helper, PurchaseFailure.INSUFFICIENT_INVENTORY, result.failure(), "失败原因应为背包不足");
                    assertEquals(helper, 100_000L, data.virtualBalance(player.getUUID()), "失败不得扣款");
                    assertEquals(helper, 500, ShopServer.INSTANCE.stock().remaining("comparator"), "失败不得扣库存");
                })

                // ---- 场景 7：/buildingshop reload 保留数据包且不重置已有库存 ----
                .thenExecute(() -> {
                    ShopServer.INSTANCE.updateStock(level, "comparator", 42);
                    assertEquals(helper, 42, ShopServer.INSTANCE.stock().remaining("comparator"), "前置库存应改为 42");
                    level.getServer().getCommands().performPrefixedCommand(
                            level.getServer().createCommandSourceStack(), "/buildingshop reload");
                })
                .thenExecuteAfter(60, () -> {
                    assertEquals(helper, 26, ShopServer.INSTANCE.catalog().productCount(), "重载后目录必须完整");
                    assertEquals(helper, 10, ShopServer.INSTANCE.catalog().categoryCount(), "重载后分类必须完整");
                    assertEquals(helper, 42, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "重载不得重置已有有限库存");
                    assertEquals(helper, 42, ShopServer.INSTANCE.dataOf(level).stockOf("comparator"),
                            "重载不得改写持久化库存");
                    ShopServer.INSTANCE.updateStock(level, "comparator", 500);
                })

                // ---- 场景 7b：stock 命令族（set/add/restock）在真实命令入口下可用 ----
                .thenExecute(() -> {
                    level.getServer().getCommands().performPrefixedCommand(
                            level.getServer().createCommandSourceStack(), "/buildingshop stock set comparator 5");
                    assertEquals(helper, 5, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "/buildingshop stock set comparator 5 应生效");
                    level.getServer().getCommands().performPrefixedCommand(
                            level.getServer().createCommandSourceStack(), "/buildingshop stock add comparator 2");
                    assertEquals(helper, 7, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "/buildingshop stock add comparator 2 应生效");
                    level.getServer().getCommands().performPrefixedCommand(
                            level.getServer().createCommandSourceStack(), "/buildingshop stock restock comparator");
                    assertEquals(helper, 500, ShopServer.INSTANCE.stock().remaining("comparator"),
                            "/buildingshop stock restock comparator 应恢复初始值 500");
                })

                // ---- 场景 8：跨维度共享同一份数据 ----
                .thenExecute(() -> {
                    ShopSavedData overworldData = ShopServer.INSTANCE.dataOf(level.getServer().overworld());
                    ServerLevel nether = level.getServer().getLevel(Level.NETHER);
                    ServerLevel end = level.getServer().getLevel(Level.END);
                    assertTrue(helper, nether != null, "下界维度应已加载");
                    assertTrue(helper, end != null, "末地维度应已加载");
                    assertTrue(helper, overworldData == ShopServer.INSTANCE.dataOf(nether),
                            "下界必须与主世界共享同一份商店数据");
                    assertTrue(helper, overworldData == ShopServer.INSTANCE.dataOf(end),
                            "末地必须与主世界共享同一份商店数据");

                    UUID playerId = UUID.randomUUID();
                    ShopServer.INSTANCE.dataOf(nether).setVirtualBalance(playerId, 700L);
                    assertEquals(helper, 700L, ShopServer.INSTANCE.dataOf(level).virtualBalance(playerId),
                            "从下界写入、从主世界读取必须可见");
                })
                .thenSucceed();
    }

    private static void assertEquals(GameTestHelper helper, Object expected, Object actual, String message) {
        helper.assertTrue(expected == null ? actual == null : expected.equals(actual),
                message + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static void assertTrue(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    private ShopGameTests() {
    }
}
