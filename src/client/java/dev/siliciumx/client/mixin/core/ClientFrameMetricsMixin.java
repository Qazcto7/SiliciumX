package dev.siliciumx.client.mixin.core;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.client.cull.AsyncOcclusionEngine;
import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.module.SiliciumXModule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advances the client frame counter and caches main camera coordinates used by short-TTL culling
 * and particle spawn/physics distance checks on every rendered frame. When
 * {@link SiliciumXModule#DIAGNOSTICS_TELEMETRY} is active, also records true frame-to-frame
 * durations into {@link SiliciumXMetrics}.
 */
@Mixin(Minecraft.class)
public abstract class ClientFrameMetricsMixin {
	@Shadow
	public ClientLevel level;

	@Shadow
	@Final
	public GameRenderer gameRenderer;

	@Unique
	private static final long SILICIUMX$MAX_VALID_FRAME_NS = 2_000_000_000L; // Ignore > 2s stalls/loading pauses

	@Unique
	private static final boolean SILICIUMX$TELEMETRY_ACTIVE =
		ModCompatibility.get().isModuleActive(SiliciumXModule.DIAGNOSTICS_TELEMETRY);

	@Unique
	private static final boolean SILICIUMX$ASYNC_OCCLUSION_ACTIVE =
		ModCompatibility.get().isModuleActive(SiliciumXModule.CLIENT_ASYNC_OCCLUSION_WORKER);

	@Unique
	private long siliciumx$lastFrameTimestampNs;

	@Inject(method = "renderFrame(Z)V", at = @At("RETURN"))
	private void siliciumx$onRenderFrameEnd(boolean renderLevel, CallbackInfo ci) {
		SiliciumXClientState.advanceFrame();
		if (this.level != null && this.gameRenderer != null) {
			Camera cam = this.gameRenderer.mainCamera();
			if (cam != null) {
				Vec3 pos = cam.position();
				SiliciumXClientState.updateCameraPosition(pos.x(), pos.y(), pos.z());
			}
		} else {
			SiliciumXClientState.clearCameraPosition();
		}

		if (!SILICIUMX$TELEMETRY_ACTIVE) {
			return;
		}
		long now = Util.getNanos();
		long prev = this.siliciumx$lastFrameTimestampNs;
		this.siliciumx$lastFrameTimestampNs = now;
		if (prev != 0L) {
			long delta = now - prev;
			if (delta > 0L && delta < SILICIUMX$MAX_VALID_FRAME_NS) {
				SiliciumXMetrics.recordClientFrameNanos(delta);
			}
		}
	}

	@Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	private void siliciumx$onClearClientLevel(Screen screen, CallbackInfo ci) {
		if (SILICIUMX$ASYNC_OCCLUSION_ACTIVE) {
			AsyncOcclusionEngine.clearAll();
		}
		SiliciumXClientState.clearCameraPosition();
		SiliciumXClientState.bumpScoreboardEpoch();
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void siliciumx$onClientClose(CallbackInfo ci) {
		if (SILICIUMX$ASYNC_OCCLUSION_ACTIVE) {
			AsyncOcclusionEngine.shutdownAll();
		}
	}
}
