package dev.silicium.client.mixin.core;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.client.cull.AsyncOcclusionEngine;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.module.SiliciumModule;
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
 * {@link SiliciumModule#DIAGNOSTICS_TELEMETRY} is active, also records true frame-to-frame
 * durations into {@link SiliciumMetrics}.
 */
@Mixin(Minecraft.class)
public abstract class ClientFrameMetricsMixin {
	@Shadow
	public ClientLevel level;

	@Shadow
	@Final
	public GameRenderer gameRenderer;

	@Unique
	private static final long SILICIUM$MAX_VALID_FRAME_NS = 2_000_000_000L; // Ignore > 2s stalls/loading pauses

	@Unique
	private static final boolean SILICIUM$TELEMETRY_ACTIVE =
		ModCompatibility.get().isModuleActive(SiliciumModule.DIAGNOSTICS_TELEMETRY);

	@Unique
	private static final boolean SILICIUM$ASYNC_OCCLUSION_ACTIVE =
		ModCompatibility.get().isModuleActive(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER);

	@Unique
	private long silicium$lastFrameTimestampNs;

	@Inject(method = "renderFrame(Z)V", at = @At("RETURN"))
	private void silicium$onRenderFrameEnd(boolean renderLevel, CallbackInfo ci) {
		SiliciumClientState.advanceFrame();
		if (this.level != null && this.gameRenderer != null) {
			Camera cam = this.gameRenderer.mainCamera();
			if (cam != null) {
				Vec3 pos = cam.position();
				SiliciumClientState.updateCameraPosition(pos.x(), pos.y(), pos.z());
			}
		} else {
			SiliciumClientState.clearCameraPosition();
		}

		if (!SILICIUM$TELEMETRY_ACTIVE) {
			return;
		}
		long now = Util.getNanos();
		long prev = this.silicium$lastFrameTimestampNs;
		this.silicium$lastFrameTimestampNs = now;
		if (prev != 0L) {
			long delta = now - prev;
			if (delta > 0L && delta < SILICIUM$MAX_VALID_FRAME_NS) {
				SiliciumMetrics.recordClientFrameNanos(delta);
			}
		}
	}

	@Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	private void silicium$onClearClientLevel(Screen screen, CallbackInfo ci) {
		if (SILICIUM$ASYNC_OCCLUSION_ACTIVE) {
			AsyncOcclusionEngine.clearAll();
		}
		SiliciumClientState.clearCameraPosition();
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void silicium$onClientClose(CallbackInfo ci) {
		if (SILICIUM$ASYNC_OCCLUSION_ACTIVE) {
			AsyncOcclusionEngine.shutdownAll();
		}
	}
}
