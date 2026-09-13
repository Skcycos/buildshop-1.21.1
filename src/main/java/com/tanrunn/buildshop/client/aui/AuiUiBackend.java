package com.tanrunn.buildshop.client.aui;

import com.sighs.apricityui.resource.Font;
import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.client.ShopScreenController;
import com.tanrunn.buildshop.client.PurchaseDashboardController;
import com.tanrunn.buildshop.client.UiBackend;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

/** ApricityUI adapter. Loaded only after UiBackendManager has confirmed AUI is installed. */
public final class AuiUiBackend implements UiBackend {
    private static final String UI_FONT = "buildshop-ui";

    @Override
    public String id() {
        return "aui";
    }

    @Override
    public void initialize() {
        registerUiFont();
    }

    @Override
    public void openShop() {
        ShopScreenController.INSTANCE.open();
    }

    @Override
    public void openDashboard() {
        PurchaseDashboardController.INSTANCE.open();
    }

    @Override
    public void applySync(SyncShopPayload payload) {
        ShopScreenController.INSTANCE.applySync(payload);
    }

    @Override
    public void applyPurchaseResult(PurchaseResultPayload payload) {
        ShopScreenController.INSTANCE.applyPurchaseResult(payload);
    }

    @Override
    public void applyShopDisabled() {
        ShopScreenController.INSTANCE.applyShopDisabled();
    }

    private static void registerUiFont() {
        try (InputStream in = Minecraft.getInstance().getResourceManager()
                .open(ResourceLocation.fromNamespaceAndPath(BuildShopMod.MODID, "fonts/noto_sans_bold.otf"))) {
            if (Font.registerFont(UI_FONT, in)) {
                BuildShopMod.LOGGER.info("Registered built-in UI font: {}", UI_FONT);
            } else {
                BuildShopMod.LOGGER.warn("Failed to register built-in UI font: {}", UI_FONT);
            }
        } catch (IOException e) {
            BuildShopMod.LOGGER.error("Failed to load built-in UI font", e);
        }
    }
}
