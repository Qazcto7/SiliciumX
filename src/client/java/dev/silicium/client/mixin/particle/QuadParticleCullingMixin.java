package dev.silicium.client.mixin.particle;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium-friendly particle extraction culler in {@link QuadParticleGroup}:
 * <ol>
 *   <li>Hoists camera coordinates and config thresholds once per group instead of per particle.</li>
 *   <li>Rejects sub-pixel distant particles beyond {@code particleMaxRenderDistance} <b>before</b>
 *       Vanilla runs its 6-plane {@link Frustum#pointInFrustum} matrix test.</li>
 *   <li>For visible particles within range, skips those completely enclosed inside full opaque
 *       solid blocks ({@code isSolidRender()}) with zero heap allocations.</li>
 * </ol>
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleCullingMixin {
	@Unique
	private BlockPos.MutableBlockPos silicium$particlePos;

	@Unique
	private double silicium$camX;
	@Unique
	private double silicium$camY;
	@Unique
	private double silicium$camZ;
	@Unique
	private double silicium$maxDistSq;
	@Unique
	private boolean silicium$cullSolid;
	@Unique
	private long silicium$culledInGroup;

	@Inject(
		method = "<init>(Lnet/minecraft/client/particle/ParticleEngine;Lnet/minecraft/client/particle/ParticleRenderType;)V",
		at = @At("RETURN")
	)
	private void silicium$initParticlePos(
		ParticleEngine particleEngine,
		ParticleRenderType renderType,
		CallbackInfo ci
	) {
		if (this.silicium$particlePos == null) {
			this.silicium$particlePos = new BlockPos.MutableBlockPos();
		}
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At("HEAD")
	)
	private void silicium$cacheGroupExtractParams(
		Frustum frustum,
		Camera camera,
		float partialTick,
		CallbackInfoReturnable<ParticleGroupRenderState> cir
	) {
		if (this.silicium$particlePos == null) {
			this.silicium$particlePos = new BlockPos.MutableBlockPos();
		}
		Vec3 camPos = camera.position();
		this.silicium$camX = camPos.x();
		this.silicium$camY = camPos.y();
		this.silicium$camZ = camPos.z();
		SiliciumConfig cfg = SiliciumConfig.get();
		this.silicium$maxDistSq = cfg.particleMaxRenderDistanceSq();
		this.silicium$cullSolid = cfg.cullParticlesInsideOpaqueBlocks;
		this.silicium$culledInGroup = 0L;
	}

	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"
		)
	)
	private boolean silicium$distancePreCullBeforeFrustum(
		Frustum frustum,
		double px,
		double py,
		double pz,
		Operation<Boolean> original
	) {
		double dx = px - this.silicium$camX;
		double dy = py - this.silicium$camY;
		double dz = pz - this.silicium$camZ;
		if ((dx * dx + dy * dy + dz * dz) > this.silicium$maxDistSq) {
			this.silicium$culledInGroup++;
			return false;
		}
		return original.call(frustum, px, py, pz);
	}

	@WrapWithCondition(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V"
		)
	)
	private boolean silicium$shouldExtractInsideSolid(
		SingleQuadParticle particle,
		QuadParticleRenderState renderState,
		Camera camera,
		float partialTick
	) {
		if (!this.silicium$cullSolid) {
			return true;
		}
		ParticleAccessor accessor = (ParticleAccessor) particle;
		double px = accessor.silicium$getX();
		double py = accessor.silicium$getY();
		double pz = accessor.silicium$getZ();
		double dx = px - this.silicium$camX;
		double dy = py - this.silicium$camY;
		double dz = pz - this.silicium$camZ;

		// Never cull particles immediately adjacent to the camera (e.g. underwater/block-breaking feedback).
		if ((dx * dx + dy * dy + dz * dz) <= 2.25) {
			return true;
		}

		ClientLevel level = accessor.silicium$getLevel();
		if (level != null) {
			this.silicium$particlePos.set(Mth.floor(px), Mth.floor(py), Mth.floor(pz));
			if (level.getBlockState(this.silicium$particlePos).isSolidRender()) {
				this.silicium$culledInGroup++;
				return false;
			}
		}
		return true;
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At("RETURN")
	)
	private void silicium$flushGroupCulledCount(
		Frustum frustum,
		Camera camera,
		float partialTick,
		CallbackInfoReturnable<ParticleGroupRenderState> cir
	) {
		SiliciumMetrics.add(SiliciumMetrics.Counter.PARTICLES_CULLED, this.silicium$culledInGroup);
		this.silicium$culledInGroup = 0L;
	}
}
