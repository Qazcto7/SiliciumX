package dev.siliciumx.mixin.core;

import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Zero-allocation server tick duration sampler feeding {@link SiliciumXMetrics} for live MSPT percentiles.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerTickMetricsMixin {
	@Unique
	private long siliciumx$tickStartNanos;

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void siliciumx$onTickStart(BooleanSupplier haveTime, CallbackInfo ci) {
		this.siliciumx$tickStartNanos = Util.getNanos();
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void siliciumx$onTickEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		long start = this.siliciumx$tickStartNanos;
		if (start != 0L) {
			SiliciumXMetrics.recordServerTickNanos(Util.getNanos() - start);
		}
	}
}
