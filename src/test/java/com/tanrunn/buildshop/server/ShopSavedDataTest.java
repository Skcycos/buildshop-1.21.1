package com.tanrunn.buildshop.server;

import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.util.datafix.DataFixers;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ShopSavedData 持久化与跨维度测试（需要真实 Minecraft 类）。
 *
 * <p>用真实 {@link DimensionDataStorage} 写入临时目录，验证：
 * 零库存持久化、初始余额标记、旧存档迁移、跨维度共享同一份数据。</p>
 */
class ShopSavedDataTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void boot() {
        Bootstrap.bootStrap();
    }

    private DimensionDataStorage storage() {
        return new DimensionDataStorage(tempDir.toFile(), DataFixers.getDataFixer(), (HolderLookup.Provider) null);
    }

    /** 构造"主世界/下界"两个虚拟 ServerLevel，共享同一个 overworld 数据存储。 */
    private ServerLevel[] fakeLevels(DimensionDataStorage overworldStorage) {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.getServer()).thenReturn(server);
        when(overworld.getDataStorage()).thenReturn(overworldStorage);
        when(server.overworld()).thenReturn(overworld);
        ServerLevel nether = mock(ServerLevel.class);
        when(nether.getServer()).thenReturn(server);
        return new ServerLevel[]{overworld, nether};
    }

    @Test
    void zeroStockPersistsAcrossReloadFromDisk() {
        DimensionDataStorage storage = storage();
        ShopSavedData data = storage.computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        data.setStock("comparator", 0);
        storage.save(); // 写入磁盘（NeoForge 经异步 IO 工作线程落盘，需等待完成）
        IOUtilities.waitUntilIOWorkerComplete();

        // 模拟重启：用同一个目录重新读盘。
        DimensionDataStorage reloaded = storage();
        ShopSavedData restored = reloaded.computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        assertEquals(0, restored.stockOf("comparator"), "卖完的 0 库存重启后必须仍为 0");
        assertTrue(restored.stock().containsKey("comparator"), "0 必须是已初始化的条目");
    }

    @Test
    void uninitializedStockIsDistinctFromZero() {
        ShopSavedData data = storage().computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        assertEquals(-1, data.stockOf("glowstone"), "未初始化返回 -1");
        data.setStock("glowstone", 2000);
        assertEquals(2000, data.stockOf("glowstone"));
        data.setStock("glowstone", 0);
        assertEquals(0, data.stockOf("glowstone"));
    }

    @Test
    void balanceInitializedFlagPersistsAcrossRestart() {
        UUID playerId = UUID.randomUUID();
        DimensionDataStorage storage = storage();
        ShopSavedData data = storage.computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        data.markBalanceInitialized(playerId);
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        DimensionDataStorage reloaded = storage();
        ShopSavedData restored = reloaded.computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        assertTrue(restored.isBalanceInitialized(playerId), "已初始化标记必须跨重启保持");
    }

    @Test
    void balanceZeroStillKeepsInitializedFlag() {
        UUID playerId = UUID.randomUUID();
        ShopSavedData data = storage().computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        data.markBalanceInitialized(playerId);
        data.setVirtualBalance(playerId, 1000);
        data.setVirtualBalance(playerId, 0); // 花光/管理员清零
        assertTrue(data.isBalanceInitialized(playerId), "余额为 0 不能丢失已初始化标记");
        assertEquals(0, data.virtualBalance(playerId));
    }

    @Test
    void oldSaveWithPositiveBalanceAutoMigratesAsInitialized() {
        // 模拟旧存档：只有 balances，没有 initBalances。
        CompoundTag oldTag = new CompoundTag();
        net.minecraft.nbt.ListTag balances = new net.minecraft.nbt.ListTag();
        UUID playerId = UUID.randomUUID();
        net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
        entry.putUUID("u", playerId);
        entry.putLong("b", 1000);
        balances.add(entry);
        oldTag.put("balances", balances);

        ShopSavedData migrated = ShopSavedData.load(oldTag, null);
        assertTrue(migrated.isBalanceInitialized(playerId), "旧存档正余额玩家必须自动视为已初始化");
        assertEquals(1000, migrated.virtualBalance(playerId));
    }

    @Test
    void oldSaveWithZeroOrNoBalanceStaysUninitialized() {
        CompoundTag oldTag = new CompoundTag();
        net.minecraft.nbt.ListTag balances = new net.minecraft.nbt.ListTag();
        UUID playerId = UUID.randomUUID();
        net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
        entry.putUUID("u", playerId);
        entry.putLong("b", 0);
        balances.add(entry);
        oldTag.put("balances", balances);

        ShopSavedData migrated = ShopSavedData.load(oldTag, null);
        assertFalse(migrated.isBalanceInitialized(playerId), "余额为 0 的旧存档玩家不能视为已初始化");
    }

    @Test
    void netherAndOverworldShareTheSameSavedData() {
        DimensionDataStorage storage = storage();
        ServerLevel[] levels = fakeLevels(storage);

        ShopSavedData overworldData = ShopSavedData.get(levels[0]);
        ShopSavedData netherData = ShopSavedData.get(levels[1]);

        assertNotNull(overworldData);
        assertSame(overworldData, netherData, "下界入口必须命中同一个 overworld SavedData 实例");

        // 从"下界"入口写入，从"主世界"入口读取必须可见。
        UUID playerId = UUID.randomUUID();
        netherData.setVirtualBalance(playerId, 500L);
        assertEquals(500L, overworldData.virtualBalance(playerId));
    }

    @Test
    void removeStockCleansEntry() {
        ShopSavedData data = storage().computeIfAbsent(new SavedData.Factory<>(ShopSavedData::new, ShopSavedData::load),
                ShopSavedData.DATA_NAME);
        data.setStock("removed", 42);
        assertEquals(42, data.stockOf("removed"));
        data.removeStock("removed");
        assertEquals(-1, data.stockOf("removed"), "删除商品后持久化条目应被清理");
    }
}
