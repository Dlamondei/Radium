package radium.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import radium.Radium;
import radium.client.gui.RadiumScreen;

public final class RadiumClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(Radium.MOD_ID, "controls")
    );

    private static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.radium.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LineSpoofer.tick(client);
            DynamicSpoofer.tick(client);

            while (openGuiKey.consumeClick()) {
                if (client.player != null) {
                    client.setScreen(new RadiumScreen());
                }
            }
        });

        // Restore the live client scoreboard before a connection is discarded,
        // then clear all world-specific lock/baseline state. The user's configured
        // entries and ON/OFF choices remain intact for the next server/world.
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> resetSessionState());
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> resetSessionState());

        Radium.LOGGER.info("Radium client initialized. Press Z to open the GUI.");
    }

    private static void resetSessionState() {
        // Dynamic restoration needs the raw prefix cache owned by LineSpoofer,
        // so restore Dynamic first and clear the shared cache last.
        DynamicSpoofer.restoreAll();
        LineSpoofer.restoreAll();
        SpoofConfig.resetRuntimeFeedback();
    }
}
