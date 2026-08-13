package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 商店持久数据（统一保存在 overworld 的 DimensionDataStorage）：
 * <ul>
 *   <li>有限库存剩余数量（服务器重启后保持；条目 {@code 0} = 明确卖完，
 *       条目缺失 = 尚未初始化，两者严格区分）</li>
 *   <li>默认虚拟货币（virtual_coins）玩家余额</li>
 *   <li>"该 UUID 已初始化余额" 标记（首次登录只发放一次初始余额）</li>
 * </ul>
 */
public class ShopSavedData extends SavedData {
    public static final String DATA_NAME = "buildshop_shop";

    private final Map<String, Integer> stock = new LinkedHashMap<>();
    private final Map<UUID, Long> virtualBalances = new LinkedHashMap<>();
    private final Set<UUID> initializedBalances = new LinkedHashSet<>();

    /**
     * 跨维度统一入口：无论传入哪个维度的 {@link ServerLevel}，都读写
     * 服务器 overworld 的数据存储，保证主世界/下界/末地看到同一份余额与库存。
     */
    public static ShopSavedData get(ServerLevel level) {
        if (level == null) return null;
        ServerLevel target = level;
        MinecraftServer server = level.getServer();
        if (server != null) {
            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                target = overworld;
            }
        }
        return target.getDataStorage().computeIfAbsent(
                new Factory<>(ShopSavedData::new, ShopSavedData::load), DATA_NAME);
    }

    public Map<String, Integer> stock() {
        return stock;
    }

    /** 缺失（未初始化）返回 -1；明确为 0 返回 0。 */
    public int stockOf(String productId) {
        return stock.getOrDefault(productId, -1);
    }

    /** 设置库存；0 也保留条目（区别于未初始化）。负值视为未初始化（移除条目）。 */
    public void setStock(String productId, int quantity) {
        if (quantity < 0) {
            stock.remove(productId);
        } else {
            stock.put(productId, quantity);
        }
        setDirty();
    }

    /** 整体替换库存（启动/重载调和后调用）。 */
    public void replaceStock(Map<String, Integer> reconciled) {
        stock.clear();
        if (reconciled != null) {
            stock.putAll(reconciled);
        }
        setDirty();
    }

    /** 商品从目录删除时清理（明确策略：与运行时同步移除持久条目）。 */
    public void removeStock(String productId) {
        if (stock.remove(productId) != null) {
            setDirty();
        }
    }

    public long virtualBalance(UUID playerId) {
        return virtualBalances.getOrDefault(playerId, 0L);
    }

    public void setVirtualBalance(UUID playerId, long amount) {
        if (amount <= 0) {
            virtualBalances.remove(playerId);
        } else {
            virtualBalances.put(playerId, amount);
        }
        setDirty();
    }

    public boolean isBalanceInitialized(UUID playerId) {
        return initializedBalances.contains(playerId);
    }

    public void markBalanceInitialized(UUID playerId) {
        if (initializedBalances.add(playerId)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag stockTag = new ListTag();
        stock.forEach((id, quantity) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            entry.putInt("q", quantity);
            stockTag.add(entry);
        });
        tag.put("stock", stockTag);

        ListTag balanceTag = new ListTag();
        virtualBalances.forEach((playerId, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("u", playerId);
            entry.putLong("b", amount);
            balanceTag.add(entry);
        });
        tag.put("balances", balanceTag);

        ListTag initializedTag = new ListTag();
        initializedBalances.forEach(playerId -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("u", playerId);
            initializedTag.add(entry);
        });
        tag.put("initBalances", initializedTag);
        return tag;
    }

    public static ShopSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopSavedData data = new ShopSavedData();
        ListTag stockTag = tag.getList("stock", Tag.TAG_COMPOUND);
        for (Tag raw : stockTag) {
            CompoundTag entry = (CompoundTag) raw;
            data.stock.put(entry.getString("id"), entry.getInt("q"));
        }
        ListTag balanceTag = tag.getList("balances", Tag.TAG_COMPOUND);
        for (Tag raw : balanceTag) {
            CompoundTag entry = (CompoundTag) raw;
            UUID playerId = entry.getUUID("u");
            data.virtualBalances.put(playerId, entry.getLong("b"));
            // 旧存档迁移：已有正余额的玩家自动视为已初始化，避免再次发放初始余额。
            if (entry.getLong("b") > 0) {
                data.initializedBalances.add(playerId);
            }
        }
        ListTag initializedTag = tag.getList("initBalances", Tag.TAG_COMPOUND);
        for (Tag raw : initializedTag) {
            CompoundTag entry = (CompoundTag) raw;
            data.initializedBalances.add(entry.getUUID("u"));
        }
        BuildShopMod.LOGGER.info("ShopSavedData loaded: {} stock entries, {} virtual balances, {} initialized balances",
                data.stock.size(), data.virtualBalances.size(), data.initializedBalances.size());
        return data;
    }
}
