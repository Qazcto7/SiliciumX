package dev.silicium.mixin.math;

import dev.silicium.util.CompactSineLut;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Vanilla's 256 KB {@code Mth.SIN} table lookups with Silicium's 64 KB quarter-wave
 * {@link CompactSineLut} for 4x better L1/L2 CPU cache residency.
 */
@Mixin(Mth.class)
public abstract class FastSineLutMixin {
	@Inject(method = "sin(D)F", at = @At("HEAD"), cancellable = true)
	private static void silicium$fastSin(double value, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(CompactSineLut.sin(value));
	}

	@Inject(method = "cos(D)F", at = @At("HEAD"), cancellable = true)
	private static void silicium$fastCos(double value, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(CompactSineLut.cos(value));
	}
}
