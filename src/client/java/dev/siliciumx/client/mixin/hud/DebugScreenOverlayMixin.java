package dev.siliciumx.client.mixin.hud;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.RollingTimingWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Throttles F3 debug text entry regeneration to a configurable interval (default 50 ms = 20 Hz)
 * while preserving full-framerate rendering for F3 performance charts, slashing F3 String/List GC
 * churn by 10x-20x at high FPS. Optionally appends a compact single-line SiliciumX telemetry readout.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Unique
	private long siliciumx$lastRefreshMs;
	@Unique
	private int siliciumx$lastEntryHash;
	@Unique
	private int siliciumx$lastEntryCount;
	@Unique
	private boolean siliciumx$lastOverlayVisible;
	@Unique
	private boolean siliciumx$lastShowF3Summary;
	@Unique
	private boolean siliciumx$useCachedFrame;
	@Unique
	private List<String> siliciumx$cachedLeftLines;
	@Unique
	private List<String> siliciumx$cachedRightLines;

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;)V", at = @At("RETURN"))
	private void siliciumx$initCacheState(Minecraft minecraft, CallbackInfo ci) {
		this.siliciumx$lastEntryCount = -1;
		this.siliciumx$cachedLeftLines = List.of();
		this.siliciumx$cachedRightLines = List.of();
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;nextStratum()V",
			ordinal = 0
		)
	)
	private void siliciumx$decideRefresh(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		SiliciumXConfig cfg = SiliciumXConfig.get();
		int intervalMs = cfg.debugHudUpdateIntervalMs;
		Collection<Identifier> enabled = this.minecraft.debugEntries.getCurrentlyEnabled();
		int entryCount = enabled.size();
		int entryHash = enabled.hashCode();
		boolean overlayVisible = this.minecraft.debugEntries.isOverlayVisible();
		boolean showSummary = cfg.showF3Summary;
		long now = Util.getMillis();

		List<String> cachedLeft = this.siliciumx$cachedLeftLines;
		List<String> cachedRight = this.siliciumx$cachedRightLines;
		boolean hasCached = cachedLeft != null && cachedRight != null
			&& (!cachedLeft.isEmpty() || !cachedRight.isEmpty());
		if (intervalMs > 0
			&& hasCached
			&& entryCount == this.siliciumx$lastEntryCount
			&& entryHash == this.siliciumx$lastEntryHash
			&& overlayVisible == this.siliciumx$lastOverlayVisible
			&& showSummary == this.siliciumx$lastShowF3Summary
			&& (now - this.siliciumx$lastRefreshMs) < intervalMs
		) {
			this.siliciumx$useCachedFrame = true;
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.DEBUG_HUD_REBUILDS_SKIPPED);
		} else {
			this.siliciumx$useCachedFrame = false;
			this.siliciumx$lastRefreshMs = now;
			this.siliciumx$lastEntryCount = entryCount;
			this.siliciumx$lastEntryHash = entryHash;
			this.siliciumx$lastOverlayVisible = overlayVisible;
			this.siliciumx$lastShowF3Summary = showSummary;
		}
	}

	@WrapWithCondition(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;display(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
		)
	)
	private boolean siliciumx$skipRedundantEntryDisplay(
		DebugScreenEntry entry,
		DebugScreenDisplayer displayer,
		Level level,
		LevelChunk clientChunk,
		LevelChunk serverChunk
	) {
		return !this.siliciumx$useCachedFrame;
	}

	@ModifyArg(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;ZI)V",
			ordinal = 0
		),
		index = 1
	)
	private List<String> siliciumx$cacheOrSupplyLeftLines(List<String> lines) {
		if (this.siliciumx$useCachedFrame && this.siliciumx$cachedLeftLines != null) {
			return this.siliciumx$cachedLeftLines;
		}
		List<String> copy = new ArrayList<>(lines.size() + 1);
		copy.addAll(lines);
		if (SiliciumXConfig.get().showF3Summary && this.minecraft.debugEntries.isOverlayVisible()) {
			copy.add(buildF3SummaryLine());
		}
		this.siliciumx$cachedLeftLines = copy;
		return copy;
	}

	@ModifyArg(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;ZI)V",
			ordinal = 1
		),
		index = 1
	)
	private List<String> siliciumx$cacheOrSupplyRightLines(List<String> lines) {
		if (this.siliciumx$useCachedFrame && this.siliciumx$cachedRightLines != null) {
			return this.siliciumx$cachedRightLines;
		}
		List<String> copy = new ArrayList<>(lines);
		this.siliciumx$cachedRightLines = copy;
		return copy;
	}

	@Inject(method = "clearChunkCache()V", at = @At("HEAD"))
	private void siliciumx$invalidateOnChunkChange(CallbackInfo ci) {
		this.siliciumx$lastRefreshMs = 0L;
		this.siliciumx$useCachedFrame = false;
	}

	@Inject(method = "reset()V", at = @At("HEAD"))
	private void siliciumx$invalidateOnReset(CallbackInfo ci) {
		this.siliciumx$lastRefreshMs = 0L;
		this.siliciumx$useCachedFrame = false;
		this.siliciumx$lastEntryCount = -1;
		this.siliciumx$cachedLeftLines = List.of();
		this.siliciumx$cachedRightLines = List.of();
	}

	@Unique
	private static String buildF3SummaryLine() {
		ModCompatibility compat = ModCompatibility.get();
		int active = compat.countActive();
		int yielded = compat.countByState(ModCompatibility.State.YIELDED_TO_MOD);
		RollingTimingWindow.Snapshot frame = SiliciumXMetrics.clientFrameSnapshot();
		long culled = SiliciumXMetrics.get(SiliciumXMetrics.Counter.PARTICLES_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.PARTICLE_SPAWNS_SKIPPED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.ENTITIES_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.BLOCK_ENTITIES_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.NAMETAGS_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.COSMETIC_LAYERS_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.ITEM_GLINTS_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.ITEM_STACKS_SIMPLIFIED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.ITEM_FRAME_ITEMS_CULLED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.WEATHER_COLUMNS_SKIPPED)
			+ SiliciumXMetrics.get(SiliciumXMetrics.Counter.SOUNDS_CULLED);
		long lmSkipped = SiliciumXMetrics.get(SiliciumXMetrics.Counter.LIGHTMAP_PASSES_SKIPPED);

		if (frame.samples() > 0) {
			return String.format(
				Locale.ROOT,
				"[SiliciumX] %d active (%d yield) | FT p95=%.1fms | culled=%,d | lmSkip=%,d",
				active, yielded, frame.p95Ms(), culled, lmSkipped
			);
		}
		return String.format(
			Locale.ROOT,
			"[SiliciumX] %d active (%d yield) | culled=%,d | lmSkip=%,d",
			active, yielded, culled, lmSkipped
		);
	}
}
