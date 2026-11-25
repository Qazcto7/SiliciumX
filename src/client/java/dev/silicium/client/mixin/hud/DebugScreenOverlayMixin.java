package dev.silicium.client.mixin.hud;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.RollingTimingWindow;
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
 * churn by 10x-20x at high FPS. Optionally appends a compact single-line Silicium telemetry readout.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Unique
	private long silicium$lastRefreshMs;
	@Unique
	private int silicium$lastEntryHash;
	@Unique
	private int silicium$lastEntryCount;
	@Unique
	private boolean silicium$lastOverlayVisible;
	@Unique
	private boolean silicium$lastShowF3Summary;
	@Unique
	private boolean silicium$useCachedFrame;
	@Unique
	private List<String> silicium$cachedLeftLines;
	@Unique
	private List<String> silicium$cachedRightLines;

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;)V", at = @At("RETURN"))
	private void silicium$initCacheState(Minecraft minecraft, CallbackInfo ci) {
		this.silicium$lastEntryCount = -1;
		this.silicium$cachedLeftLines = List.of();
		this.silicium$cachedRightLines = List.of();
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;nextStratum()V",
			ordinal = 0
		)
	)
	private void silicium$decideRefresh(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		SiliciumConfig cfg = SiliciumConfig.get();
		int intervalMs = cfg.debugHudUpdateIntervalMs;
		Collection<Identifier> enabled = this.minecraft.debugEntries.getCurrentlyEnabled();
		int entryCount = enabled.size();
		int entryHash = enabled.hashCode();
		boolean overlayVisible = this.minecraft.debugEntries.isOverlayVisible();
		boolean showSummary = cfg.showF3Summary;
		long now = Util.getMillis();

		List<String> cachedLeft = this.silicium$cachedLeftLines;
		List<String> cachedRight = this.silicium$cachedRightLines;
		boolean hasCached = cachedLeft != null && cachedRight != null
			&& (!cachedLeft.isEmpty() || !cachedRight.isEmpty());
		if (intervalMs > 0
			&& hasCached
			&& entryCount == this.silicium$lastEntryCount
			&& entryHash == this.silicium$lastEntryHash
			&& overlayVisible == this.silicium$lastOverlayVisible
			&& showSummary == this.silicium$lastShowF3Summary
			&& (now - this.silicium$lastRefreshMs) < intervalMs
		) {
			this.silicium$useCachedFrame = true;
			SiliciumMetrics.increment(SiliciumMetrics.Counter.DEBUG_HUD_REBUILDS_SKIPPED);
		} else {
			this.silicium$useCachedFrame = false;
			this.silicium$lastRefreshMs = now;
			this.silicium$lastEntryCount = entryCount;
			this.silicium$lastEntryHash = entryHash;
			this.silicium$lastOverlayVisible = overlayVisible;
			this.silicium$lastShowF3Summary = showSummary;
		}
	}

	@WrapWithCondition(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;display(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
		)
	)
	private boolean silicium$skipRedundantEntryDisplay(
		DebugScreenEntry entry,
		DebugScreenDisplayer displayer,
		Level level,
		LevelChunk clientChunk,
		LevelChunk serverChunk
	) {
		return !this.silicium$useCachedFrame;
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
	private List<String> silicium$cacheOrSupplyLeftLines(List<String> lines) {
		if (this.silicium$useCachedFrame && this.silicium$cachedLeftLines != null) {
			return this.silicium$cachedLeftLines;
		}
		List<String> copy = new ArrayList<>(lines.size() + 1);
		copy.addAll(lines);
		if (SiliciumConfig.get().showF3Summary && this.minecraft.debugEntries.isOverlayVisible()) {
			copy.add(buildF3SummaryLine());
		}
		this.silicium$cachedLeftLines = copy;
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
	private List<String> silicium$cacheOrSupplyRightLines(List<String> lines) {
		if (this.silicium$useCachedFrame && this.silicium$cachedRightLines != null) {
			return this.silicium$cachedRightLines;
		}
		List<String> copy = new ArrayList<>(lines);
		this.silicium$cachedRightLines = copy;
		return copy;
	}

	@Inject(method = "clearChunkCache()V", at = @At("HEAD"))
	private void silicium$invalidateOnChunkChange(CallbackInfo ci) {
		this.silicium$lastRefreshMs = 0L;
		this.silicium$useCachedFrame = false;
	}

	@Inject(method = "reset()V", at = @At("HEAD"))
	private void silicium$invalidateOnReset(CallbackInfo ci) {
		this.silicium$lastRefreshMs = 0L;
		this.silicium$useCachedFrame = false;
		this.silicium$lastEntryCount = -1;
		this.silicium$cachedLeftLines = List.of();
		this.silicium$cachedRightLines = List.of();
	}

	@Unique
	private static String buildF3SummaryLine() {
		ModCompatibility compat = ModCompatibility.get();
		int active = compat.countActive();
		int yielded = compat.countByState(ModCompatibility.State.YIELDED_TO_MOD);
		RollingTimingWindow.Snapshot frame = SiliciumMetrics.clientFrameSnapshot();
		long culled = SiliciumMetrics.get(SiliciumMetrics.Counter.PARTICLES_CULLED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.PARTICLE_SPAWNS_SKIPPED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.ENTITIES_CULLED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.BLOCK_ENTITIES_CULLED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.WEATHER_COLUMNS_SKIPPED)
			+ SiliciumMetrics.get(SiliciumMetrics.Counter.SOUNDS_CULLED);
		long lmSkipped = SiliciumMetrics.get(SiliciumMetrics.Counter.LIGHTMAP_PASSES_SKIPPED);

		if (frame.samples() > 0) {
			return String.format(
				Locale.ROOT,
				"[Silicium] %d active (%d yield) | FT p95=%.1fms | culled=%,d | lmSkip=%,d",
				active, yielded, frame.p95Ms(), culled, lmSkipped
			);
		}
		return String.format(
			Locale.ROOT,
			"[Silicium] %d active (%d yield) | culled=%,d | lmSkip=%,d",
			active, yielded, culled, lmSkipped
		);
	}
}
