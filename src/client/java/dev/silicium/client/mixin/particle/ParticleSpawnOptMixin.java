package dev.silicium.client.mixin.particle;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rejects non-priority particle spawns beyond {@code particleMaxRenderDistance + 4} blocks inside
 * {@link ParticleEngine#createParticle} before any {@link Particle} instance is allocated or
 * queued into a {@code ParticleGroup}.
 *
 * <p>Never suppresses particles marked with {@code overrideLimiter} (explosions, fireworks,
 * campfire signal smoke, sonic booms, etc.).</p>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleSpawnOptMixin {
	@Inject(
		method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void silicium$skipOffDistanceSpawn(
		ParticleOptions options,
		double x,
		double y,
		double z,
		double xd,
		double yd,
		double zd,
		CallbackInfoReturnable<Particle> cir
	) {
		if (options == null || options.getType().getOverrideLimiter() || !SiliciumClientState.hasCameraPosition()) {
			return;
		}
		double dx = x - SiliciumClientState.cameraX();
		double dy = y - SiliciumClientState.cameraY();
		double dz = z - SiliciumClientState.cameraZ();
		if ((dx * dx + dy * dy + dz * dz) > SiliciumConfig.get().particleSpawnMaxDistanceSq()) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.PARTICLE_SPAWNS_SKIPPED);
			cir.setReturnValue(null);
		}
	}
}
