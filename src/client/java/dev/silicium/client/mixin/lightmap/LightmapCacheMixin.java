package dev.silicium.client.mixin.lightmap;

import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates redundant GPU UBO buffer maps and render passes inside {@link Lightmap#render(LightmapRenderState)}
 * when all lightmap uniform inputs have changed by less than a sub-LSB threshold (&lt; 1/255 channel step).
 *
 * <p>Works cleanly on both OpenGL and Vulkan RenderPearl backends and is 100% Sodium-friendly.</p>
 */
@Mixin(Lightmap.class)
public abstract class LightmapCacheMixin {
	@Unique
	private boolean silicium$hasCachedState;
	@Unique
	private float silicium$skyFactor;
	@Unique
	private float silicium$blockFactor;
	@Unique
	private float silicium$nightVision;
	@Unique
	private float silicium$darkness;
	@Unique
	private float silicium$bossDarkening;
	@Unique
	private float silicium$brightness;
	@Unique
	private float silicium$blockR, silicium$blockG, silicium$blockB;
	@Unique
	private float silicium$skyR, silicium$skyG, silicium$skyB;
	@Unique
	private float silicium$ambR, silicium$ambG, silicium$ambB;
	@Unique
	private float silicium$nvR, silicium$nvG, silicium$nvB;

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void silicium$skipRedundantLightmapPass(LightmapRenderState state, CallbackInfo ci) {
		if (!state.needsUpdate) {
			return;
		}
		Vector3fc blockTint = state.blockLightTint;
		Vector3fc skyColor = state.skyLightColor;
		Vector3fc ambient = state.ambientColor;
		Vector3fc nvColor = state.nightVisionColor;
		if (blockTint == null || skyColor == null || ambient == null || nvColor == null) {
			this.silicium$hasCachedState = false;
			return;
		}

		float eps = SiliciumConfig.get().lightmapEpsilon;
		if (this.silicium$hasCachedState
			&& approxEqual(this.silicium$skyFactor, state.skyFactor, eps)
			&& approxEqual(this.silicium$blockFactor, state.blockFactor, eps)
			&& approxEqual(this.silicium$nightVision, state.nightVisionEffectIntensity, eps)
			&& approxEqual(this.silicium$darkness, state.darknessEffectScale, eps)
			&& approxEqual(this.silicium$bossDarkening, state.bossOverlayWorldDarkening, eps)
			&& approxEqual(this.silicium$brightness, state.brightness, eps)
			&& approxEqualVec(this.silicium$blockR, this.silicium$blockG, this.silicium$blockB, blockTint, eps)
			&& approxEqualVec(this.silicium$skyR, this.silicium$skyG, this.silicium$skyB, skyColor, eps)
			&& approxEqualVec(this.silicium$ambR, this.silicium$ambG, this.silicium$ambB, ambient, eps)
			&& approxEqualVec(this.silicium$nvR, this.silicium$nvG, this.silicium$nvB, nvColor, eps)
		) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.LIGHTMAP_PASSES_SKIPPED);
			ci.cancel();
			return;
		}

		this.silicium$skyFactor = state.skyFactor;
		this.silicium$blockFactor = state.blockFactor;
		this.silicium$nightVision = state.nightVisionEffectIntensity;
		this.silicium$darkness = state.darknessEffectScale;
		this.silicium$bossDarkening = state.bossOverlayWorldDarkening;
		this.silicium$brightness = state.brightness;

		this.silicium$blockR = blockTint.x();
		this.silicium$blockG = blockTint.y();
		this.silicium$blockB = blockTint.z();

		this.silicium$skyR = skyColor.x();
		this.silicium$skyG = skyColor.y();
		this.silicium$skyB = skyColor.z();

		this.silicium$ambR = ambient.x();
		this.silicium$ambG = ambient.y();
		this.silicium$ambB = ambient.z();

		this.silicium$nvR = nvColor.x();
		this.silicium$nvG = nvColor.y();
		this.silicium$nvB = nvColor.z();

		this.silicium$hasCachedState = true;
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void silicium$onClose(CallbackInfo ci) {
		this.silicium$hasCachedState = false;
	}

	@Unique
	private static boolean approxEqual(float a, float b, float eps) {
		return Math.abs(a - b) <= eps;
	}

	@Unique
	private static boolean approxEqualVec(float r, float g, float b, Vector3fc vec, float eps) {
		return Math.abs(r - vec.x()) <= eps
			&& Math.abs(g - vec.y()) <= eps
			&& Math.abs(b - vec.z()) <= eps;
	}
}
