package dev.silicium.mixin.core;

import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Zero-allocation server tick duration sampler feeding {@link SiliciumMetrics} for live MSPT percentiles.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerTickMetricsMixin {
	@Unique
	private long silicium$tickStartNanos;

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void silicium$onTickStart(BooleanSupplier haveTime, CallbackInfo ci) {
		this.silicium$tickStartNanos = Util.getNanos();
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void silicium$onTickEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		long start = this.silicium$tickStartNanos;
		if (start != 0L) {
			SiliciumMetrics.recordServerTickNanos(Util.getNanos() - start);
		}
	}
}
