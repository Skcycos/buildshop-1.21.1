package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.api.ShopCurrencyProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 货币提供者注册表。 */
public final class CurrencyRegistry {
    public static final CurrencyRegistry INSTANCE = new CurrencyRegistry();

    private final Map<String, ShopCurrencyProvider> providers = new LinkedHashMap<>();

    private CurrencyRegistry() {
    }

    /** 注册（重复 ID 覆盖）。 */
    public synchronized void register(ShopCurrencyProvider provider) {
        if (provider == null) return;
        providers.put(provider.id(), provider);
    }

    public synchronized Optional<ShopCurrencyProvider> get(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public synchronized Map<String, ShopCurrencyProvider> all() {
        return Map.copyOf(providers);
    }

    /** 默认提供者：虚拟货币 + 物品货币（在服务端启动时注册）。 */
    public static void registerDefaults() {
        if (!INSTANCE.get(VirtualCurrencyProvider.ID).isPresent()) {
            INSTANCE.register(new VirtualCurrencyProvider());
        }
    }
}
