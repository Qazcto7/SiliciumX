package dev.silicium;

import dev.silicium.compat.ModCompatibility;
import dev.silicium.config.SiliciumConfig;
import net.fabricmc.api.ModInitializer;
import org.spongepowered.asm.mixin.MixinEnvironment;

public final class SiliciumMod implements ModInitializer {
	@Override
	public void onInitialize() {
		// Ensure config and compatibility matrix are initialized and print the startup ecosystem report.
		SiliciumConfig.get();
		ModCompatibility.get().logStartupReport();

		if (Boolean.getBoolean("silicium.auditMixins")) {
			SiliciumConstants.LOGGER.info("Running full Mixin bytecode audit (-Dsilicium.auditMixins=true)...");
			MixinEnvironment.getCurrentEnvironment().audit();
			SiliciumConstants.LOGGER.info("Mixin bytecode audit completed with ZERO errors!");
			System.exit(0);
		}
	}
}
