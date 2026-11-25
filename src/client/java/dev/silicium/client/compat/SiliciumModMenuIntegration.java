package dev.silicium.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.silicium.client.gui.SiliciumConfigScreen;

/**
 * Optional Mod Menu integration entrypoint for Silicium.
 * Only loaded by Fabric Loader when Mod Menu is installed.
 */
public final class SiliciumModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SiliciumConfigScreen::new;
	}
}
