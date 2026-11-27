package dev.siliciumx.client.mixin.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips submitting sub-pixel held-item models on distant entities beyond {@code cosmeticLayerMaxDistance}.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerCullingMixin {
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$cullDistantHeldItems(
		PoseStack poseStack,
		SubmitNodeCollector collector,
		int lightCoords,
		ArmedEntityRenderState state,
		float yRot,
		float xRot,
		CallbackInfo ci
	) {
		if (state.distanceToCameraSq > SiliciumXConfig.get().cosmeticLayerMaxDistanceSq()) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.COSMETIC_LAYERS_CULLED);
			ci.cancel();
		}
	}
}
