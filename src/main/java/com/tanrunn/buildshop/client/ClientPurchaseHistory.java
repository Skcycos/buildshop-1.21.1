package com.tanrunn.buildshop.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 建材商店客户端历史。只记录客户端收到的成功购买结果，最多保留 200 笔。
 * 真实扣款、发货和库存仍完全由服务端完成。
 */
public final class ClientPurchaseHistory {
    public static final ClientPurchaseHistory INSTANCE = new ClientPurchaseHistory();
    public static final int MAX_RECORDS = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger("buildshop.client.history");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ClientPurchaseRecord>>() {
    }.getType();

    private final List<ClientPurchaseRecord> records = new ArrayList<>();
    private boolean loaded;

    private ClientPurchaseHistory() {
    }

    public synchronized List<ClientPurchaseRecord> records() {
        ensureLoaded();
        return List.copyOf(records);
    }

    public synchronized void add(ClientPurchaseRecord record) {
        if (record == null || record.quantity() <= 0 || record.totalPrice() < 0) return;
        ensureLoaded();
        records.add(0, record);
        while (records.size() > MAX_RECORDS) records.remove(records.size() - 1);
        save();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) return;
        try {
            List<ClientPurchaseRecord> parsed = GSON.fromJson(Files.readString(path), LIST_TYPE);
            if (parsed != null) {
                for (ClientPurchaseRecord record : parsed) {
                    if (record != null && record.quantity() > 0 && record.totalPrice() >= 0) {
                        records.add(record);
                    }
                    if (records.size() >= MAX_RECORDS) break;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Unable to load local purchase history; starting with an empty list", e);
            records.clear();
        }
    }

    private void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(records, LIST_TYPE));
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Unable to save local purchase history", e);
        }
    }

    private Path path() {
        Path config = FMLPaths.CONFIGDIR.get();
        if (config == null) {
            config = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        }
        return config.resolve("buildshop-purchase-history.json");
    }
}
