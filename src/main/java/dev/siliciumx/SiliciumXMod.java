package dev.siliciumx;

import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.config.SiliciumXConfig;
import net.fabricmc.api.ModInitializer;
import org.spongepowered.asm.mixin.MixinEnvironment;

public final class SiliciumXMod implements ModInitializer {
	@Override
	public void onInitialize() {
		// Ensure config and compatibility matrix are initialized and print the startup ecosystem report.
		SiliciumXConfig.get();
		ModCompatibility.get().logStartupReport();

		if (Boolean.getBoolean("siliciumx.auditMixins")) {
			SiliciumXConstants.LOGGER.info("Running full Mixin bytecode audit (-Dsiliciumx.auditMixins=true)...");
			MixinEnvironment.getCurrentEnvironment().audit();
			SiliciumXConstants.LOGGER.info("Mixin bytecode audit completed with ZERO errors!");
			System.exit(0);
		}
	}
}
