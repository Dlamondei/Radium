package radium.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import radium.client.gui.RadiumScreen;

/**
 * Optional Mod Menu bridge. Fabric only asks for this entrypoint when Mod Menu
 * is present, so Radium has no runtime dependency on Mod Menu.
 */
public final class RadiumModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RadiumScreen::new;
    }
}
