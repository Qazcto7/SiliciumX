package dev.siliciumx.client.mixin.cull;

import dev.siliciumx.client.cull.ItemGlintStripper;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Implements zero-allocation enchantment glint stripping on {@link ItemStackRenderState} for
 * distant dropped items and distant item frames.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemGlintStripper {
	@Shadow
	private int activeLayerCount;

	@Shadow
	private ItemStackRenderState.LayerRenderState[] layers;

	@Override
	public boolean siliciumx$stripGlint() {
		int count = this.activeLayerCount;
		ItemStackRenderState.LayerRenderState[] arr = this.layers;
		if (count <= 0 || arr == null) {
			return false;
		}
		int limit = Math.min(count, arr.length);
		boolean stripped = false;
		for (int i = 0; i < limit; i++) {
			ItemStackRenderState.LayerRenderState layer = arr[i];
			if (layer != null && ((ItemLayerStateAccessor) (Object) layer).siliciumx$getFoilType() != ItemStackRenderState.FoilType.NONE) {
				layer.setFoilType(ItemStackRenderState.FoilType.NONE);
				stripped = true;
			}
		}
		return stripped;
	}
}
