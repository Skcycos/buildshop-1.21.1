package com.tanrunn.buildshop;

import com.mojang.logging.LogUtils;
import com.tanrunn.buildshop.network.BuildShopNetwork;
import com.tanrunn.buildshop.server.CurrencyRegistry;
import com.tanrunn.buildshop.server.ShopCommands;
import com.tanrunn.buildshop.server.ShopDataLoader;
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
        }

        @SubscribeEvent
        public static void onCommands(RegisterCommandsEvent event) {
            ShopCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                // 首次登录发放初始余额
                var data = ShopServer.INSTANCE.dataOf(player);
                if (data.virtualBalance(player.getUUID()) <= 0 && Config.VIRTUAL_INITIAL_BALANCE.get() > 0) {
                    data.setVirtualBalance(player.getUUID(), Config.VIRTUAL_INITIAL_BALANCE.get());
                }
                ShopServer.INSTANCE.syncTo(player);
            }
        }
    }
}
