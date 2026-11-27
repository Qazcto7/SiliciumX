package dev.siliciumx.client.mixin.hud;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.client.hud.ScoreboardSidebarCache;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.locale.Language;
import net.minecraft.util.Util;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;

/**
 * Caches the sidebar scoreboard layout inside {@link Hud#displayScoreboardSidebar} while drawing
 * identical Vanilla fill and text primitives every frame.
 *
 * <p>Automatically invalidated on any {@link Scoreboard} mutation, font reload, or language swap.</p>
 */
@Mixin(Hud.class)
public abstract class ScoreboardSidebarCacheMixin {
	@Shadow
	@Final
	private static Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER;

	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	public abstract Font getFont();

	@Unique
	private ScoreboardSidebarCache siliciumx$sidebarCache;

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;)V", at = @At("RETURN"))
	private void siliciumx$initSidebarCache(Minecraft minecraft, CallbackInfo ci) {
		this.siliciumx$sidebarCache = new ScoreboardSidebarCache();
	}

	@Unique
	private ScoreboardSidebarCache siliciumx$getOrCreateSidebarCache() {
		ScoreboardSidebarCache cache = this.siliciumx$sidebarCache;
		if (cache == null) {
			cache = new ScoreboardSidebarCache();
			this.siliciumx$sidebarCache = cache;
		}
		return cache;
	}

	@Inject(
		method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$displayCachedScoreboardSidebar(
		GuiGraphicsExtractor graphics,
		Objective objective,
		CallbackInfo ci
	) {
		if (SiliciumXConfig.get().hudCacheUpdateIntervalMs <= 0) {
			return;
		}
		ci.cancel();

		Font font = this.getFont();
		Scoreboard scoreboard = objective.getScoreboard();
		long sbEpoch = SiliciumXClientState.scoreboardEpoch();
		int fontEpoch = SiliciumXClientState.fontEpoch();
		Language lang = Language.getInstance();
		long nowMs = Util.getMillis();

		ScoreboardSidebarCache cache = this.siliciumx$getOrCreateSidebarCache();
		if (cache.isValid(objective, scoreboard, sbEpoch, fontEpoch, lang, nowMs)) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.HUD_LAYOUTS_CACHED);
		} else {
			cache.rebuild(objective, scoreboard, SCORE_DISPLAY_ORDER, font, sbEpoch, fontEpoch, lang, nowMs);
		}

		ScoreboardSidebarCache.Row[] rows = cache.rows();
		int entryCount = rows.length;
		int lineHeight = 9;
		int totalHeight = entryCount * lineHeight;
		int bottomY = graphics.guiHeight() / 2 + totalHeight / 3;
		int maxWidth = cache.maxWidth();
		int leftX = graphics.guiWidth() - maxWidth - 3;
		int rightX = graphics.guiWidth() - 3 + 2;
		int bodyBg = this.minecraft.options.getBackgroundColor(0.3f);
		int headerBg = this.minecraft.options.getBackgroundColor(0.4f);
		int topY = bottomY - entryCount * lineHeight;

		graphics.fill(leftX - 2, topY - lineHeight - 1, rightX, topY - 1, headerBg);
		graphics.fill(leftX - 2, topY - 1, rightX, bottomY, bodyBg);
		graphics.text(font, cache.title(), leftX + maxWidth / 2 - cache.titleWidth() / 2, topY - lineHeight, -1, false);

		for (int i = 0; i < entryCount; i++) {
			ScoreboardSidebarCache.Row row = rows[i];
			int rowY = bottomY - (entryCount - i) * lineHeight;
			graphics.text(font, row.name(), leftX, rowY, -1, false);
			graphics.text(font, row.score(), rightX - row.scoreWidth(), rowY, -1, false);
		}
	}
}
