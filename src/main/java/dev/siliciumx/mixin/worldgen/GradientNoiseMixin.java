package dev.siliciumx.mixin.worldgen;

import dev.siliciumx.util.FastPerlinNoise;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates {@code GradientNoise.GRADIENT[]} heap record indirection and {@code int -> float}
 * conversions in {@link GradientNoise#gradDot(int, float, float, float)}.
 */
@Mixin(GradientNoise.class)
public abstract class GradientNoiseMixin {
	@Inject(method = "gradDot(IFFF)F", at = @At("HEAD"), cancellable = true)
	private static void siliciumx$fastGradDot(
		int hash,
		float x,
		float y,
		float z,
		CallbackInfoReturnable<Float> cir
	) {
		cir.setReturnValue(FastPerlinNoise.gradDot(hash, x, y, z));
	}
}
