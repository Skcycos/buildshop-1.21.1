package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商店持久数据：
 * <ul>
 *   <li>有限库存剩余数量（服务器重启后保持）</li>
 *   <li>默认虚拟货币（virtual_coins）玩家余额</li>
 * </ul>
 */
public class ShopSavedData extends SavedData {
    public static final String DATA_NAME = "buildshop_shop";

    private final Map<String, Integer> stock = new LinkedHashMap<>();
    private final Map<UUID, Long> virtualBalances = new LinkedHashMap<>();

    public static ShopSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(ShopSavedData::new, ShopSavedData::load), DATA_NAME);
    }

    public Map<String, Integer> stock() {
        return stock;
    }

    public int stockOf(String productId) {
        return stock.getOrDefault(productId, -1);
    }

    public void setStock(String productId, int quantity) {
        if (quantity <= 0) {
            stock.remove(productId);
        } else {
            stock.put(productId, quantity);
        }
        setDirty();
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
        }
        BuildShopMod.LOGGER.info("ShopSavedData loaded: {} stock entries, {} virtual balances",
                data.stock.size(), data.virtualBalances.size());
        return data;
    }
}
