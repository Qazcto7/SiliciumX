package dev.silicium.client.mixin.hud;

import dev.silicium.client.SiliciumClientState;
import net.minecraft.client.Options;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps Silicium's font cache epoch whenever resource packs reload or font options change so
 * {@link StringSplitterWidthCacheMixin} never serves stale glyph widths.
 */
@Mixin(FontManager.class)
public abstract class FontManagerReloadMixin {
	@Inject(
		method = "apply(Lnet/minecraft/client/gui/font/FontManager$Preparation;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At("TAIL")
	)
	private void silicium$onFontsReloaded(@Coerce Object preparation, ProfilerFiller profiler, CallbackInfo ci) {
		SiliciumClientState.invalidateFontCaches();
	}

	@Inject(method = "updateOptions(Lnet/minecraft/client/Options;)V", at = @At("TAIL"))
	private void silicium$onFontOptionsUpdated(Options options, CallbackInfo ci) {
		SiliciumClientState.invalidateFontCaches();
	}
}
