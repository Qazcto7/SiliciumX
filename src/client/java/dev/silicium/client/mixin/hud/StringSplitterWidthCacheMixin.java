package dev.silicium.client.mixin.hud;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.StringFloatCache;
import net.minecraft.client.StringSplitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-instance bounded cache for {@link StringSplitter#stringWidth(String)} (512 slots, strings
 * &lt;= 128 chars), eliminating per-call {@code MutableFloat} and {@code FormattedCharSink} lambda
 * allocations across HUD, F3, chat, scoreboard, tooltips, and menus.
 *
 * <p>Automatically invalidated whenever fonts reload or Unicode font options change via
 * {@link FontManagerReloadMixin}.</p>
 */
@Mixin(StringSplitter.class)
public abstract class StringSplitterWidthCacheMixin {
	@Unique
	private StringFloatCache silicium$widthCache;

	@Unique
	private int silicium$lastFontEpoch;

	@Unique
	private volatile Thread silicium$ownerThread;

	@Inject(method = "<init>(Lnet/minecraft/client/StringSplitter$WidthProvider;)V", at = @At("RETURN"))
	private void silicium$initWidthCache(StringSplitter.WidthProvider widthProvider, CallbackInfo ci) {
		if (this.silicium$widthCache == null) {
			this.silicium$widthCache = new StringFloatCache(512, 128);
			this.silicium$lastFontEpoch = -1;
		}
	}

	@Inject(method = "stringWidth(Ljava/lang/String;)F", at = @At("HEAD"), cancellable = true)
	private void silicium$lookupCachedWidth(String text, CallbackInfoReturnable<Float> cir) {
		Thread current = Thread.currentThread();
		Thread owner = this.silicium$ownerThread;
		if (owner == null) {
			this.silicium$ownerThread = current;
		} else if (owner != current) {
			return;
		}
		if (this.silicium$widthCache == null) {
			this.silicium$widthCache = new StringFloatCache(512, 128);
			this.silicium$lastFontEpoch = -1;
		}
		if (!this.silicium$widthCache.isEligible(text)) {
			return;
		}
		int epoch = SiliciumClientState.fontEpoch();
		if (epoch != this.silicium$lastFontEpoch) {
			this.silicium$widthCache.clear();
			this.silicium$lastFontEpoch = epoch;
			return;
		}
		float cached = this.silicium$widthCache.get(text);
		if (!Float.isNaN(cached)) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.TEXT_WIDTH_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "stringWidth(Ljava/lang/String;)F", at = @At("RETURN"))
	private void silicium$storeComputedWidth(String text, CallbackInfoReturnable<Float> cir) {
		if (this.silicium$widthCache == null || Thread.currentThread() != this.silicium$ownerThread) {
			return;
		}
		if (this.silicium$widthCache.isEligible(text)) {
			this.silicium$lastFontEpoch = SiliciumClientState.fontEpoch();
			this.silicium$widthCache.put(text, cir.getReturnValueF());
		}
	}
}
