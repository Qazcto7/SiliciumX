package dev.siliciumx.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.siliciumx.client.gui.SiliciumXConfigScreen;

/**
 * Optional Mod Menu integration entrypoint for SiliciumX.
 * Only loaded by Fabric Loader when Mod Menu is installed.
 */
public final class SiliciumXModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SiliciumXConfigScreen::new;
	}
}
