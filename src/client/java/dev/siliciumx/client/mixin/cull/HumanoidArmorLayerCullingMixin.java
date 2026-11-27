package dev.siliciumx.client.mixin.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips submitting distant armor layer geometry for non-player humanoid entities (mobs, armor
 * stands) beyond {@code cosmeticLayerMaxDistance}, while always keeping player armor visible for
 * PvP clarity.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerCullingMixin {
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$cullDistantMobArmor(
		PoseStack poseStack,
		SubmitNodeCollector collector,
		int lightCoords,
		HumanoidRenderState state,
		float yRot,
		float xRot,
		CallbackInfo ci
	) {
		if (!(state instanceof AvatarRenderState)
			&& state.distanceToCameraSq > SiliciumXConfig.get().cosmeticLayerMaxDistanceSq()
		) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.COSMETIC_LAYERS_CULLED);
			ci.cancel();
		}
	}
}
