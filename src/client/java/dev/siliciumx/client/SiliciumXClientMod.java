package dev.siliciumx.client;

import dev.siliciumx.SiliciumXConstants;
import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.module.SiliciumXModule;
import net.fabricmc.api.ClientModInitializer;

public final class SiliciumXClientMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModCompatibility compat = ModCompatibility.get();
		int activeGpu = 0;
		for (SiliciumXModule module : SiliciumXModule.values()) {
			if (module.environment() == SiliciumXModule.Environment.CLIENT && compat.isModuleActive(module)) {
				activeGpu++;
			}
		}
		SiliciumXConstants.LOGGER.info(
			"SiliciumX client ready: {} Sodium-friendly GPU/render modules active.",
			activeGpu
		);
	}
}
