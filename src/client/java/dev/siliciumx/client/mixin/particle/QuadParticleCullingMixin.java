package dev.siliciumx.client.mixin.particle;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
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
	private BlockPos.MutableBlockPos siliciumx$particlePos;

	@Unique
	private double siliciumx$camX;
	@Unique
	private double siliciumx$camY;
	@Unique
	private double siliciumx$camZ;
	@Unique
	private double siliciumx$maxDistSq;
	@Unique
	private boolean siliciumx$cullSolid;
	@Unique
	private long siliciumx$culledInGroup;

	@Inject(
		method = "<init>(Lnet/minecraft/client/particle/ParticleEngine;Lnet/minecraft/client/particle/ParticleRenderType;)V",
		at = @At("RETURN")
	)
	private void siliciumx$initParticlePos(
		ParticleEngine particleEngine,
		ParticleRenderType renderType,
		CallbackInfo ci
	) {
		if (this.siliciumx$particlePos == null) {
			this.siliciumx$particlePos = new BlockPos.MutableBlockPos();
		}
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At("HEAD")
	)
	private void siliciumx$cacheGroupExtractParams(
		Frustum frustum,
		Camera camera,
		float partialTick,
		CallbackInfoReturnable<ParticleGroupRenderState> cir
	) {
		if (this.siliciumx$particlePos == null) {
			this.siliciumx$particlePos = new BlockPos.MutableBlockPos();
		}
		Vec3 camPos = camera.position();
		this.siliciumx$camX = camPos.x();
		this.siliciumx$camY = camPos.y();
		this.siliciumx$camZ = camPos.z();
		SiliciumXConfig cfg = SiliciumXConfig.get();
		this.siliciumx$maxDistSq = cfg.particleMaxRenderDistanceSq();
		this.siliciumx$cullSolid = cfg.cullParticlesInsideOpaqueBlocks;
		this.siliciumx$culledInGroup = 0L;
	}

	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"
		)
	)
	private boolean siliciumx$distancePreCullBeforeFrustum(
		Frustum frustum,
		double px,
		double py,
		double pz,
		Operation<Boolean> original
	) {
		double dx = px - this.siliciumx$camX;
		double dy = py - this.siliciumx$camY;
		double dz = pz - this.siliciumx$camZ;
		if ((dx * dx + dy * dy + dz * dz) > this.siliciumx$maxDistSq) {
			this.siliciumx$culledInGroup++;
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
	private boolean siliciumx$shouldExtractInsideSolid(
		SingleQuadParticle particle,
		QuadParticleRenderState renderState,
		Camera camera,
		float partialTick
	) {
		if (!this.siliciumx$cullSolid) {
			return true;
		}
		ParticleAccessor accessor = (ParticleAccessor) particle;
		double px = accessor.siliciumx$getX();
		double py = accessor.siliciumx$getY();
		double pz = accessor.siliciumx$getZ();
		double dx = px - this.siliciumx$camX;
		double dy = py - this.siliciumx$camY;
		double dz = pz - this.siliciumx$camZ;

		// Never cull particles immediately adjacent to the camera (e.g. underwater/block-breaking feedback).
		if ((dx * dx + dy * dy + dz * dz) <= 2.25) {
			return true;
		}

		ClientLevel level = accessor.siliciumx$getLevel();
		if (level != null) {
			this.siliciumx$particlePos.set(Mth.floor(px), Mth.floor(py), Mth.floor(pz));
			if (level.getBlockState(this.siliciumx$particlePos).isSolidRender()) {
				this.siliciumx$culledInGroup++;
				return false;
			}
		}
		return true;
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
		at = @At("RETURN")
	)
	private void siliciumx$flushGroupCulledCount(
		Frustum frustum,
		Camera camera,
		float partialTick,
		CallbackInfoReturnable<ParticleGroupRenderState> cir
	) {
		SiliciumXMetrics.add(SiliciumXMetrics.Counter.PARTICLES_CULLED, this.siliciumx$culledInGroup);
		this.siliciumx$culledInGroup = 0L;
	}
}
