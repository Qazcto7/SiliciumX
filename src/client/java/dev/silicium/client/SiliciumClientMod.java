package dev.silicium.client;

import dev.silicium.SiliciumConstants;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.module.SiliciumModule;
import net.fabricmc.api.ClientModInitializer;

public final class SiliciumClientMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModCompatibility compat = ModCompatibility.get();
		int activeGpu = 0;
		for (SiliciumModule module : SiliciumModule.values()) {
			if (module.environment() == SiliciumModule.Environment.CLIENT && compat.isModuleActive(module)) {
				activeGpu++;
			}
		}
		SiliciumConstants.LOGGER.info(
			"Silicium client ready: {} Sodium-friendly GPU/render modules active.",
			activeGpu
		);
	}
}
