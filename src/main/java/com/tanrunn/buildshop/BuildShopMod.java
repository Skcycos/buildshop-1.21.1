package com.tanrunn.buildshop;

import com.mojang.logging.LogUtils;
import com.tanrunn.buildshop.network.BuildShopNetwork;
import com.tanrunn.buildshop.server.CurrencyRegistry;
import com.tanrunn.buildshop.server.ShopCommands;
import com.tanrunn.buildshop.server.ShopDataLoader;
import com.tanrunn.buildshop.server.ShopSavedData;
import com.tanrunn.buildshop.server.ShopServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BuildShopMod.MODID)
public class BuildShopMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "buildshop";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ShopDataLoader DATA_LOADER = new ShopDataLoader();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BuildShopMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("{} initialized", MODID);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(BuildShopNetwork.CHANNEL)
                .versioned("1")
                .optional();
        BuildShopNetwork.register(registrar);
        LOGGER.info("Registered {} network payloads", BuildShopNetwork.class.getSimpleName());
    }

    // 服务端主线程事件
    @EventBusSubscriber(modid = BuildShopMod.MODID)
    public static class ServerEvents {

        @SubscribeEvent
        public static void onAddReloadListener(AddReloadListenerEvent event) {
            event.addListener(DATA_LOADER.categoriesListener());
            event.addListener(DATA_LOADER.productsListener());
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            CurrencyRegistry.registerDefaults();
            // 把已加载的目录应用到 overworld SavedData 与运行时 StockStore：
            // 首次启动时目录可能在 overworld 就绪前就已加载，这里统一补做库存初始化。
            ShopServer.INSTANCE.onServerStarted(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            ShopServer.INSTANCE.onServerStopped();
        }

        @SubscribeEvent
        public static void onCommands(RegisterCommandsEvent event) {
            ShopCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                // 首次登录发放初始余额：持久化的"已初始化"标记保证只发一次，
                // 余额花光/管理员清零/重新登录都不会再次领取；初始余额配置为 0 也记录标记。
                ShopSavedData data = ShopServer.INSTANCE.dataOf(player);
                if (!data.isBalanceInitialized(player.getUUID())) {
                    data.markBalanceInitialized(player.getUUID());
                    long initial = Config.VIRTUAL_INITIAL_BALANCE.get();
                    if (initial > 0 && data.virtualBalance(player.getUUID()) <= 0) {
                        data.setVirtualBalance(player.getUUID(), initial);
                    }
                }
                ShopServer.INSTANCE.syncTo(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                // 清理玩家级幂等缓存与节流状态，避免长期开服无限保留 UUID。
                ShopServer.INSTANCE.onPlayerLoggedOut(player);
            }
        }
    }
}
