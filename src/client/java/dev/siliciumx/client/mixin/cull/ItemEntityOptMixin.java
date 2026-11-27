package dev.siliciumx.client.mixin.cull;

import dev.siliciumx.client.cull.ItemGlintStripper;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes distant dropped item entities in {@link ItemEntityRenderer}:
 * <ol>
 *   <li>Strips sub-pixel enchantment glint passes beyond {@code itemGlintMaxDistance}.</li>
 *   <li>Optionally collapses multi-copy item cluster geometry ({@code count > 1} &rarr; {@code 1})
 *       beyond {@code itemGlintMaxDistance}, slashing item farm draw load while keeping every
 *       dropped item visible.</li>
 * </ol>
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityOptMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
		at = @At("TAIL")
	)
	private void siliciumx$optimizeDistantItemEntity(
		ItemEntity entity,
		ItemEntityRenderState state,
		float partialTick,
		CallbackInfo ci
	) {
		SiliciumXConfig cfg = SiliciumXConfig.get();
		if (state.distanceToCameraSq <= cfg.itemGlintMaxDistanceSq()) {
			return;
		}
		if (!state.item.isEmpty() && ((ItemGlintStripper) (Object) state.item).siliciumx$stripGlint()) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ITEM_GLINTS_CULLED);
		}
		if (cfg.simplifyDistantItemStacks && state.count > 1) {
			state.count = 1;
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ITEM_STACKS_SIMPLIFIED);
		}
	}
}
