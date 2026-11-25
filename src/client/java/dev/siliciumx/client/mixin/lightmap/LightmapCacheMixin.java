package dev.siliciumx.client.mixin.lightmap;

import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
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
	private boolean siliciumx$hasCachedState;
	@Unique
	private float siliciumx$skyFactor;
	@Unique
	private float siliciumx$blockFactor;
	@Unique
	private float siliciumx$nightVision;
	@Unique
	private float siliciumx$darkness;
	@Unique
	private float siliciumx$bossDarkening;
	@Unique
	private float siliciumx$brightness;
	@Unique
	private float siliciumx$blockR, siliciumx$blockG, siliciumx$blockB;
	@Unique
	private float siliciumx$skyR, siliciumx$skyG, siliciumx$skyB;
	@Unique
	private float siliciumx$ambR, siliciumx$ambG, siliciumx$ambB;
	@Unique
	private float siliciumx$nvR, siliciumx$nvG, siliciumx$nvB;

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void siliciumx$skipRedundantLightmapPass(LightmapRenderState state, CallbackInfo ci) {
		if (!state.needsUpdate) {
			return;
		}
		Vector3fc blockTint = state.blockLightTint;
		Vector3fc skyColor = state.skyLightColor;
		Vector3fc ambient = state.ambientColor;
		Vector3fc nvColor = state.nightVisionColor;
		if (blockTint == null || skyColor == null || ambient == null || nvColor == null) {
			this.siliciumx$hasCachedState = false;
			return;
		}

		float eps = SiliciumXConfig.get().lightmapEpsilon;
		if (this.siliciumx$hasCachedState
			&& approxEqual(this.siliciumx$skyFactor, state.skyFactor, eps)
			&& approxEqual(this.siliciumx$blockFactor, state.blockFactor, eps)
			&& approxEqual(this.siliciumx$nightVision, state.nightVisionEffectIntensity, eps)
			&& approxEqual(this.siliciumx$darkness, state.darknessEffectScale, eps)
			&& approxEqual(this.siliciumx$bossDarkening, state.bossOverlayWorldDarkening, eps)
			&& approxEqual(this.siliciumx$brightness, state.brightness, eps)
			&& approxEqualVec(this.siliciumx$blockR, this.siliciumx$blockG, this.siliciumx$blockB, blockTint, eps)
			&& approxEqualVec(this.siliciumx$skyR, this.siliciumx$skyG, this.siliciumx$skyB, skyColor, eps)
			&& approxEqualVec(this.siliciumx$ambR, this.siliciumx$ambG, this.siliciumx$ambB, ambient, eps)
			&& approxEqualVec(this.siliciumx$nvR, this.siliciumx$nvG, this.siliciumx$nvB, nvColor, eps)
		) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.LIGHTMAP_PASSES_SKIPPED);
			ci.cancel();
			return;
		}

		this.siliciumx$skyFactor = state.skyFactor;
		this.siliciumx$blockFactor = state.blockFactor;
		this.siliciumx$nightVision = state.nightVisionEffectIntensity;
		this.siliciumx$darkness = state.darknessEffectScale;
		this.siliciumx$bossDarkening = state.bossOverlayWorldDarkening;
		this.siliciumx$brightness = state.brightness;

		this.siliciumx$blockR = blockTint.x();
		this.siliciumx$blockG = blockTint.y();
		this.siliciumx$blockB = blockTint.z();

		this.siliciumx$skyR = skyColor.x();
		this.siliciumx$skyG = skyColor.y();
		this.siliciumx$skyB = skyColor.z();

		this.siliciumx$ambR = ambient.x();
		this.siliciumx$ambG = ambient.y();
		this.siliciumx$ambB = ambient.z();

		this.siliciumx$nvR = nvColor.x();
		this.siliciumx$nvG = nvColor.y();
		this.siliciumx$nvB = nvColor.z();

		this.siliciumx$hasCachedState = true;
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void siliciumx$onClose(CallbackInfo ci) {
		this.siliciumx$hasCachedState = false;
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
