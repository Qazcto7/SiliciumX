package dev.silicium.client.mixin.hud;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.RichTextFloatCache;
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
	private RichTextFloatCache silicium$richWidthCache;

	@Unique
	private int silicium$lastRichFontEpoch;

	@Unique
	private Language silicium$lastLanguage;

	@Unique
	private volatile Thread silicium$richOwnerThread;

	@Inject(method = "<init>(Lnet/minecraft/client/StringSplitter$WidthProvider;)V", at = @At("RETURN"))
	private void silicium$initRichWidthCache(StringSplitter.WidthProvider widthProvider, CallbackInfo ci) {
		if (this.silicium$richWidthCache == null) {
			this.silicium$richWidthCache = new RichTextFloatCache(512, 128);
			this.silicium$lastRichFontEpoch = -1;
		}
	}

	@Inject(method = "stringWidth(Lnet/minecraft/network/chat/FormattedText;)F", at = @At("HEAD"), cancellable = true)
	private void silicium$lookupCachedRichWidth(FormattedText text, CallbackInfoReturnable<Float> cir) {
		if (!(text instanceof Component comp) || !comp.getSiblings().isEmpty()) {
			return;
		}
		ComponentContents contents = comp.getContents();
		if (contents == PlainTextContents.EMPTY) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.RICH_TEXT_WIDTH_HITS);
			cir.setReturnValue(0.0f);
			return;
		}

		String keyText;
		boolean translatable;
		if (contents instanceof PlainTextContents plain) {
			keyText = plain.text();
			if (keyText.isEmpty()) {
				SiliciumMetrics.increment(SiliciumMetrics.Counter.RICH_TEXT_WIDTH_HITS);
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
		Thread owner = this.silicium$richOwnerThread;
		if (owner == null) {
			this.silicium$richOwnerThread = current;
		} else if (owner != current) {
			return;
		}

		if (this.silicium$richWidthCache == null) {
			this.silicium$richWidthCache = new RichTextFloatCache(512, 128);
			this.silicium$lastRichFontEpoch = -1;
		}
		if (!this.silicium$richWidthCache.isEligible(keyText)) {
			return;
		}

		int epoch = SiliciumClientState.fontEpoch();
		Language lang = Language.getInstance();
		if (epoch != this.silicium$lastRichFontEpoch || lang != this.silicium$lastLanguage) {
			this.silicium$richWidthCache.clear();
			this.silicium$lastRichFontEpoch = epoch;
			this.silicium$lastLanguage = lang;
			return;
		}

		float cached = this.silicium$richWidthCache.get(keyText, comp.getStyle(), translatable);
		if (!Float.isNaN(cached)) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.RICH_TEXT_WIDTH_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "stringWidth(Lnet/minecraft/network/chat/FormattedText;)F", at = @At("RETURN"))
	private void silicium$storeComputedRichWidth(FormattedText text, CallbackInfoReturnable<Float> cir) {
		if (this.silicium$richWidthCache == null || Thread.currentThread() != this.silicium$richOwnerThread) {
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

		if (this.silicium$richWidthCache.isEligible(keyText)) {
			Style style = comp.getStyle();
			this.silicium$lastRichFontEpoch = SiliciumClientState.fontEpoch();
			this.silicium$lastLanguage = Language.getInstance();
			this.silicium$richWidthCache.put(keyText, style, translatable, cir.getReturnValueF());
		}
	}
}
