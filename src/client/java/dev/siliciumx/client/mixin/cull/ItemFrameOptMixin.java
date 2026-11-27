package dev.siliciumx.client.mixin.cull;

import dev.siliciumx.client.cull.ItemGlintStripper;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes distant non-map item frames in {@link ItemFrameRenderer}:
 * <ol>
 *   <li>Strips sub-pixel enchantment glint beyond {@code itemGlintMaxDistance}.</li>
 *   <li>Skips extracting tiny interior item models beyond {@code itemFrameItemMaxDistance} while
 *       preserving the frame itself and never touching map-art frames ({@code mapId != null}).</li>
 * </ol>
 */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameOptMixin<T extends ItemFrame> {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
		at = @At("TAIL")
	)
	private void siliciumx$optimizeDistantItemFrame(
		T entity,
		ItemFrameRenderState state,
		float partialTick,
		CallbackInfo ci
	) {
		if (state.mapId != null || state.item.isEmpty()) {
			return;
		}
		SiliciumXConfig cfg = SiliciumXConfig.get();
		if (state.distanceToCameraSq > cfg.itemFrameItemMaxDistanceSq()) {
			state.item.clear();
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ITEM_FRAME_ITEMS_CULLED);
		} else if (state.distanceToCameraSq > cfg.itemGlintMaxDistanceSq()) {
			if (((ItemGlintStripper) (Object) state.item).siliciumx$stripGlint()) {
				SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ITEM_GLINTS_CULLED);
			}
		}
	}
}
