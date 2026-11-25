package dev.siliciumx.client.mixin.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
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
	private boolean siliciumx$skipDistantParticleCollision(Particle instance, Operation<Boolean> original) {
		if (!original.call(instance)) {
			return false;
		}
		if (!SiliciumXClientState.hasCameraPosition()) {
			return true;
		}
		double dx = this.x - SiliciumXClientState.cameraX();
		double dy = this.y - SiliciumXClientState.cameraY();
		double dz = this.z - SiliciumXClientState.cameraZ();
		if ((dx * dx + dy * dy + dz * dz) > SiliciumXConfig.get().particlePhysicsMaxDistanceSq()) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.PARTICLE_COLLISIONS_SKIPPED);
			return false;
		}
		return true;
	}
}
