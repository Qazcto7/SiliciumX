package dev.siliciumx.client.mixin.hud;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.RichTextFloatCache;
import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-instance bounded width cache for {@link StringSplitter#stringWidth(FormattedText)} covering
 * single-span literal components and zero-argument translatable components (buttons, labels,
 * tooltips, scoreboard lines, nametags, and chat entries).
 *
 * <p>Keys are extracted as immutable {@code (textOrKey, Style, translatable)} tuples so in-place
 * mutations of {@code MutableComponent} can never cause stale cache hits. Automatically invalidated
 * on both font reloads ({@link FontManagerReloadMixin}) and {@link Language} swaps.</p>
 */
@Mixin(StringSplitter.class)
public abstract class RichTextWidthCacheMixin {
	@Unique
	private RichTextFloatCache siliciumx$richWidthCache;

	@Unique
	private int siliciumx$lastRichFontEpoch;

	@Unique
	private Language siliciumx$lastLanguage;

	@Unique
	private volatile Thread siliciumx$richOwnerThread;

	@Inject(method = "<init>(Lnet/minecraft/client/StringSplitter$WidthProvider;)V", at = @At("RETURN"))
	private void siliciumx$initRichWidthCache(StringSplitter.WidthProvider widthProvider, CallbackInfo ci) {
		if (this.siliciumx$richWidthCache == null) {
			this.siliciumx$richWidthCache = new RichTextFloatCache(512, 128);
			this.siliciumx$lastRichFontEpoch = -1;
		}
	}

	@Inject(method = "stringWidth(Lnet/minecraft/network/chat/FormattedText;)F", at = @At("HEAD"), cancellable = true)
	private void siliciumx$lookupCachedRichWidth(FormattedText text, CallbackInfoReturnable<Float> cir) {
		if (!(text instanceof Component comp) || !comp.getSiblings().isEmpty()) {
			return;
		}
		ComponentContents contents = comp.getContents();
		if (contents == PlainTextContents.EMPTY) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.RICH_TEXT_WIDTH_HITS);
			cir.setReturnValue(0.0f);
			return;
		}

		String keyText;
		boolean translatable;
		if (contents instanceof PlainTextContents plain) {
			keyText = plain.text();
			if (keyText.isEmpty()) {
				SiliciumXMetrics.increment(SiliciumXMetrics.Counter.RICH_TEXT_WIDTH_HITS);
				cir.setReturnValue(0.0f);
				return;
			}
			translatable = false;
		} else if (contents instanceof TranslatableContents tc
			&& tc.getArgs().length == 0
			&& tc.getFallback() == null
		) {
			keyText = tc.getKey();
			translatable = true;
		} else {
			return;
		}

		Thread current = Thread.currentThread();
		Thread owner = this.siliciumx$richOwnerThread;
		if (owner == null) {
			this.siliciumx$richOwnerThread = current;
		} else if (owner != current) {
			return;
		}

		if (this.siliciumx$richWidthCache == null) {
			this.siliciumx$richWidthCache = new RichTextFloatCache(512, 128);
			this.siliciumx$lastRichFontEpoch = -1;
		}
		if (!this.siliciumx$richWidthCache.isEligible(keyText)) {
			return;
		}

		int epoch = SiliciumXClientState.fontEpoch();
		Language lang = Language.getInstance();
		if (epoch != this.siliciumx$lastRichFontEpoch || lang != this.siliciumx$lastLanguage) {
			this.siliciumx$richWidthCache.clear();
			this.siliciumx$lastRichFontEpoch = epoch;
			this.siliciumx$lastLanguage = lang;
			return;
		}

		float cached = this.siliciumx$richWidthCache.get(keyText, comp.getStyle(), translatable);
		if (!Float.isNaN(cached)) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.RICH_TEXT_WIDTH_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "stringWidth(Lnet/minecraft/network/chat/FormattedText;)F", at = @At("RETURN"))
	private void siliciumx$storeComputedRichWidth(FormattedText text, CallbackInfoReturnable<Float> cir) {
		if (this.siliciumx$richWidthCache == null || Thread.currentThread() != this.siliciumx$richOwnerThread) {
			return;
		}
		if (!(text instanceof Component comp) || !comp.getSiblings().isEmpty()) {
			return;
		}
		ComponentContents contents = comp.getContents();
		String keyText;
		boolean translatable;
		if (contents instanceof PlainTextContents plain) {
			keyText = plain.text();
			translatable = false;
		} else if (contents instanceof TranslatableContents tc
			&& tc.getArgs().length == 0
			&& tc.getFallback() == null
		) {
			keyText = tc.getKey();
			translatable = true;
		} else {
			return;
		}

		if (this.siliciumx$richWidthCache.isEligible(keyText)) {
			Style style = comp.getStyle();
			this.siliciumx$lastRichFontEpoch = SiliciumXClientState.fontEpoch();
			this.siliciumx$lastLanguage = Language.getInstance();
			this.siliciumx$richWidthCache.put(keyText, style, translatable, cir.getReturnValueF());
		}
	}
}
