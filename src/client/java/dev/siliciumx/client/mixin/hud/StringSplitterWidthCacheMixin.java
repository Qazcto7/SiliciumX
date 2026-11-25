package dev.siliciumx.client.mixin.hud;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.StringFloatCache;
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
	private StringFloatCache siliciumx$widthCache;

	@Unique
	private int siliciumx$lastFontEpoch;

	@Unique
	private volatile Thread siliciumx$ownerThread;

	@Inject(method = "<init>(Lnet/minecraft/client/StringSplitter$WidthProvider;)V", at = @At("RETURN"))
	private void siliciumx$initWidthCache(StringSplitter.WidthProvider widthProvider, CallbackInfo ci) {
		if (this.siliciumx$widthCache == null) {
			this.siliciumx$widthCache = new StringFloatCache(512, 128);
			this.siliciumx$lastFontEpoch = -1;
		}
	}

	@Inject(method = "stringWidth(Ljava/lang/String;)F", at = @At("HEAD"), cancellable = true)
	private void siliciumx$lookupCachedWidth(String text, CallbackInfoReturnable<Float> cir) {
		Thread current = Thread.currentThread();
		Thread owner = this.siliciumx$ownerThread;
		if (owner == null) {
			this.siliciumx$ownerThread = current;
		} else if (owner != current) {
			return;
		}
		if (this.siliciumx$widthCache == null) {
			this.siliciumx$widthCache = new StringFloatCache(512, 128);
			this.siliciumx$lastFontEpoch = -1;
		}
		if (!this.siliciumx$widthCache.isEligible(text)) {
			return;
		}
		int epoch = SiliciumXClientState.fontEpoch();
		if (epoch != this.siliciumx$lastFontEpoch) {
			this.siliciumx$widthCache.clear();
			this.siliciumx$lastFontEpoch = epoch;
			return;
		}
		float cached = this.siliciumx$widthCache.get(text);
		if (!Float.isNaN(cached)) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.TEXT_WIDTH_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "stringWidth(Ljava/lang/String;)F", at = @At("RETURN"))
	private void siliciumx$storeComputedWidth(String text, CallbackInfoReturnable<Float> cir) {
		if (this.siliciumx$widthCache == null || Thread.currentThread() != this.siliciumx$ownerThread) {
			return;
		}
		if (this.siliciumx$widthCache.isEligible(text)) {
			this.siliciumx$lastFontEpoch = SiliciumXClientState.fontEpoch();
			this.siliciumx$widthCache.put(text, cir.getReturnValueF());
		}
	}
}
