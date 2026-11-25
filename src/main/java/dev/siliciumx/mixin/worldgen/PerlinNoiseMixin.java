package dev.siliciumx.mixin.worldgen;

import dev.siliciumx.util.FastPerlinNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Vanilla's 14 virtual {@code permute()} calls, 8 {@code GRADIENT[]} record dereferences,
 * and nested {@code Mth.smoothstep}/{@code lerp3} dispatches inside {@link PerlinNoise#sampleAndLerp}
 * with {@link FastPerlinNoise#sampleAndLerp} (100% bit-for-bit identical worldgen output).
 */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin {
	@Inject(method = "sampleAndLerp(IIIFFFF)F", at = @At("HEAD"), cancellable = true)
	private void siliciumx$fastSampleAndLerp(
		int x,
		int y,
		int z,
		float fx,
		float fy,
		float fz,
		float yFade,
		CallbackInfoReturnable<Float> cir
	) {
		byte[] perms = ((GradientNoiseAccessor) this).siliciumx$getPerms();
		cir.setReturnValue(FastPerlinNoise.sampleAndLerp(perms, x, y, z, fx, fy, fz, yFade));
	}
}
