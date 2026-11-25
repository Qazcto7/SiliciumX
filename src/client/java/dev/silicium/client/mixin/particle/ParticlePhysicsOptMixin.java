package dev.silicium.client.mixin.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silicium.client.SiliciumClientState;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.particle.Particle;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips expensive {@code Entity.collideBoundingBox} VoxelShape collision tests and temporary
 * {@code Vec3}/{@code CollisionContext} allocations inside {@link Particle#move} for particles
 * farther than {@code particlePhysicsMaxDistance} (default 24 blocks) from the camera.
 */
@Mixin(Particle.class)
public abstract class ParticlePhysicsOptMixin {
	@Shadow
	protected double x;
	@Shadow
	protected double y;
	@Shadow
	protected double z;

	@WrapOperation(
		method = "move(DDD)V",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/particle/Particle;hasPhysics:Z",
			opcode = Opcodes.GETFIELD
		)
	)
	private boolean silicium$skipDistantParticleCollision(Particle instance, Operation<Boolean> original) {
		if (!original.call(instance)) {
			return false;
		}
		if (!SiliciumClientState.hasCameraPosition()) {
			return true;
		}
		double dx = this.x - SiliciumClientState.cameraX();
		double dy = this.y - SiliciumClientState.cameraY();
		double dz = this.z - SiliciumClientState.cameraZ();
		if ((dx * dx + dy * dy + dz * dz) > SiliciumConfig.get().particlePhysicsMaxDistanceSq()) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.PARTICLE_COLLISIONS_SKIPPED);
			return false;
		}
		return true;
	}
}
