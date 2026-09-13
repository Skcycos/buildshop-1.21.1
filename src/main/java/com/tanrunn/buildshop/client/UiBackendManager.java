package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.Config;
import com.tanrunn.buildshop.network.BuildShopNetwork.PurchaseResultPayload;
import com.tanrunn.buildshop.network.BuildShopNetwork.SyncShopPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.Locale;

/**
 * Chooses and dispatches to the configured client UI backend.
 *
 * <p>Optional UI implementations are loaded by class name only. This is important for a
 * dedicated server and for clients that install just one of the two UI libraries: the JVM never
 * resolves the missing library's classes while selecting or dispatching packets.</p>
 */
public final class UiBackendManager {
    public static final UiBackendManager INSTANCE = new UiBackendManager();

    static final String AUI = "aui";
    static final String LDLIB2 = "ldlib2";
    private static final String AUI_MOD_ID = "apricityui";
    private static final String LDLIB2_MOD_ID = "ldlib2";
    private static final String AUI_CLASS = "com.tanrunn.buildshop.client.aui.AuiUiBackend";
    private static final String LDLIB2_CLASS = "com.tanrunn.buildshop.client.ldlib2.LdLib2UiBackend";

    private UiBackend backend = new MissingUiBackend();
    private String configuredBackend = AUI;
    /** Latest backend explicitly selected by the connected server; null before the first shop-open packet. */
    private String serverBackend;
    private boolean initialized;

    private UiBackendManager() {
    }

    /** Called from the client setup event, before the first server packet can open a shop. */
    public synchronized void initialize() {
        configure(readConfiguredBackend());
    }

    public void openShop() {
        dispatch(UiBackend::openShop);
    }

    public void openDashboard() {
        dispatch(UiBackend::openDashboard);
    }

    public void applySync(SyncShopPayload payload) {
        dispatch(backend -> backend.applySync(payload));
    }

    public void applyPurchaseResult(PurchaseResultPayload payload) {
        dispatch(backend -> backend.applyPurchaseResult(payload));
    }

    public void applyShopDisabled() {
        dispatch(UiBackend::applyShopDisabled);
    }

    public synchronized String activeBackendId() {
        return backend.id();
    }

    /**
     * Receives the server-owned selection bundled with {@code OpenShopPayload}.
     * The server value wins over the local bootstrap default for the rest of this connection.
     */
    public synchronized void setServerBackend(String requested) {
        serverBackend = normalize(requested);
        if (!initialized || !serverBackend.equals(configuredBackend)) {
            configure(serverBackend);
        }
    }

    private void dispatch(java.util.function.Consumer<UiBackend> action) {
        UiBackend selected;
        synchronized (this) {
            // SERVER config synchronization happens after client setup. Re-read the value before
            // dispatching packets so a server selecting LDLib2 is not stuck on the local default.
            ensureBackendCurrent();
            selected = backend;
        }
        try {
            action.accept(selected);
        } catch (Throwable error) {
            BuildShopMod.LOGGER.error("Building Shop UI backend '{}' failed", selected.id(), error);
            if (!(selected instanceof MissingUiBackend)) {
                synchronized (this) {
                    backend = new MissingUiBackend();
                    backend.initialize();
                }
                backend.openShop();
            }
        }
    }

    private String readConfiguredBackend() {
        if (serverBackend != null) {
            return serverBackend;
        }
        try {
            return normalize(Config.UI_BACKEND.get());
        } catch (IllegalStateException notLoadedYet) {
            // SERVER config is not reliably available during FMLClientSetup. The connected
            // server's value is supplied explicitly by OpenShopPayload before the first screen.
            return AUI;
        } catch (RuntimeException error) {
            BuildShopMod.LOGGER.warn("Unable to read buildshop-server.toml uiBackend; using aui", error);
            return AUI;
        }
    }

    private void ensureBackendCurrent() {
        String requested = readConfiguredBackend();
        if (!initialized || !requested.equals(configuredBackend)) {
            configure(requested);
        }
    }

    private void configure(String requested) {
        configuredBackend = normalize(requested);
        backend = createBackend(configuredBackend);
        initialized = true;
        try {
            backend.initialize();
        } catch (Throwable error) {
            BuildShopMod.LOGGER.error("Unable to initialize UI backend '{}'", backend.id(), error);
            backend = new MissingUiBackend();
            backend.initialize();
        }
        BuildShopMod.LOGGER.info("Building Shop UI backend selected: {}", backend.id());
    }

    private UiBackend createBackend(String preferred) {
        boolean auiAvailable = ModList.get().isLoaded(AUI_MOD_ID);
        boolean ldlib2Available = ModList.get().isLoaded(LDLIB2_MOD_ID);
        String first = preferred;
        String second = AUI.equals(first) ? LDLIB2 : AUI;

        UiBackend result = tryCreate(first, AUI.equals(first) ? auiAvailable : ldlib2Available);
        if (result != null) return result;
        result = tryCreate(second, AUI.equals(second) ? auiAvailable : ldlib2Available);
        if (result != null) {
            BuildShopMod.LOGGER.warn("Configured UI backend '{}' is unavailable; falling back to '{}'", first, second);
            return result;
        }

        BuildShopMod.LOGGER.error("Neither ApricityUI nor LDLib2 is installed; Building Shop will show a safe error screen");
        return new MissingUiBackend();
    }

    /** Pure selection rule kept package-visible for unit tests and future backend adapters. */
    static String selectAvailableBackend(String configured, boolean auiAvailable, boolean ldlib2Available) {
        String preferred = normalize(configured);
        if ((AUI.equals(preferred) && auiAvailable) || (LDLIB2.equals(preferred) && ldlib2Available)) {
            return preferred;
        }
        String fallback = AUI.equals(preferred) ? LDLIB2 : AUI;
        if ((AUI.equals(fallback) && auiAvailable) || (LDLIB2.equals(fallback) && ldlib2Available)) {
            return fallback;
        }
        return null;
    }

    private UiBackend tryCreate(String id, boolean modLoaded) {
        if (!modLoaded) return null;
        String className = AUI.equals(id) ? AUI_CLASS : LDLIB2_CLASS;
        try {
            Class<?> implementation = Class.forName(className, true, UiBackendManager.class.getClassLoader());
            Object instance = implementation.getDeclaredConstructor().newInstance();
            if (!(instance instanceof UiBackend value)) {
                BuildShopMod.LOGGER.error("UI backend class {} does not implement UiBackend", className);
                return null;
            }
            return value;
        } catch (Throwable error) {
            BuildShopMod.LOGGER.warn("Unable to load UI backend '{}' from {}", id, className, error);
            return null;
        }
    }

    static String normalize(String value) {
        if (value == null) return AUI;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return AUI.equals(normalized) || LDLIB2.equals(normalized) ? normalized : AUI;
    }

    /** Vanilla-only fallback that can be loaded with neither optional UI library installed. */
    private static final class MissingUiBackend implements UiBackend {
        @Override
        public String id() {
            return "missing";
        }

        @Override
        public void initialize() {
        }

        @Override
        public void openShop() {
            Minecraft.getInstance().setScreen(new MissingUiScreen());
        }

        @Override
        public void openDashboard() {
            openShop();
        }

        @Override
        public void applySync(SyncShopPayload payload) {
        }

        @Override
        public void applyPurchaseResult(PurchaseResultPayload payload) {
        }

        @Override
        public void applyShopDisabled() {
        }
    }

    private static final class MissingUiScreen extends Screen {
        private MissingUiScreen() {
            super(Component.translatable("buildshop.ui.backend_missing.title"));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, Component.translatable("buildshop.ui.backend_missing.title"), width / 2, height / 2 - 24, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("buildshop.ui.backend_missing.message"), width / 2, height / 2, 0xE0E0E0);
            graphics.drawCenteredString(font, Component.translatable("buildshop.ui.backend_missing.hint"), width / 2, height / 2 + 18, 0xAAAAAA);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (minecraft.options.keyInventory.matches(keyCode, scanCode) || keyCode == 256) {
                onClose();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
