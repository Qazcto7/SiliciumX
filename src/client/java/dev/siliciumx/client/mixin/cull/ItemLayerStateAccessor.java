package dev.siliciumx.client.mixin.cull;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for {@link ItemStackRenderState.LayerRenderState}'s foil type so glint
 * stripping only writes and counts telemetry when foil is actually present.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerStateAccessor {
	@Accessor("foilType")
	ItemStackRenderState.FoilType siliciumx$getFoilType();
}
