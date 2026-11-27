package dev.siliciumx.client.cull;

/**
 * Duck interface implemented on {@code ItemStackRenderState} to strip distant sub-pixel
 * enchantment glint layers without altering the underlying item model geometry.
 */
public interface ItemGlintStripper {
	/**
	 * Clears enchantment foil from all active layers in this render state.
	 *
	 * @return {@code true} if at least one layer had foil stripped
	 */
	boolean siliciumx$stripGlint();
}
