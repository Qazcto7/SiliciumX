package dev.silicium.client.mixin.cull;

import dev.silicium.client.cull.AsyncOcclusionEngine;
import dev.silicium.client.cull.OcclusionRaycaster;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.module.SiliciumModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conservative, Sodium-friendly 9-point solid-block occlusion culling for entities in
 * {@link LevelExtractor#isEntityVisible}.
 *
 * <p>Automatically yields when EntityCulling, MoreCulling, Iris, or Oculus is installed.</p>
 */
@Mixin(LevelExtractor.class)
public abstract class EntityOcclusionMixin {
	@Shadow
	private ClientLevel level;

	@Shadow
	@Final
	private Minecraft minecraft;

	@Unique
	private static final boolean SILICIUM$USE_ASYNC =
		ModCompatibility.get().isModuleActive(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER);

	// Keep @Unique instance fields uninitialized so Mixin never depends on LineNumberTable initializer slicing.
	@Unique
	private AsyncOcclusionEngine silicium$asyncRaycaster;

	@Unique
	private OcclusionRaycaster silicium$entityRaycaster;

	@Inject(
		method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/LevelRenderer;)V",
		at = @At("RETURN")
	)
	private void silicium$initRaycasters(
		Minecraft minecraft,
		LevelRenderState levelRenderState,
		LevelRenderer levelRenderer,
		CallbackInfo ci
	) {
		this.silicium$ensureRaycasters();
	}

	@Unique
	private void silicium$ensureRaycasters() {
		if (SILICIUM$USE_ASYNC) {
			if (this.silicium$asyncRaycaster == null) {
				this.silicium$asyncRaycaster = new AsyncOcclusionEngine("Entity");
			}
		} else if (this.silicium$entityRaycaster == null) {
			this.silicium$entityRaycaster = new OcclusionRaycaster();
		}
	}

	@Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
	private void silicium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
		if (this.silicium$asyncRaycaster != null) {
			this.silicium$asyncRaycaster.clear(newLevel);
		}
		if (this.silicium$entityRaycaster != null) {
			this.silicium$entityRaycaster.clear();
		}
	}

	@Inject(
		method = "isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z",
		at = @At("RETURN"),
		cancellable = true
	)
	private void silicium$cullOccludedEntity(
		Entity entity,
		Frustum frustum,
		double camX,
		double camY,
		double camZ,
		float partialTick,
		long sectionTime,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!cir.getReturnValue() || this.level == null) {
			return;
		}
		if (isExemptFromCulling(entity)) {
			return;
		}

		double dx = entity.getX() - camX;
		double dy = entity.getY() - camY;
		double dz = entity.getZ() - camZ;
		if ((dx * dx + dy * dy + dz * dz) < SiliciumConfig.get().entityCullingMinDistanceSq()) {
			return;
		}

		AABB box = entity.getBoundingBox();
		if (box.hasNaN() || box.getSize() <= 0.0) {
			return;
		}

		this.silicium$ensureRaycasters();
		boolean occluded = this.silicium$asyncRaycaster != null
			? this.silicium$asyncRaycaster.isOccluded(this.level, entity.getId(), camX, camY, camZ, box)
			: this.silicium$entityRaycaster.isOccluded(this.level, entity.getId(), camX, camY, camZ, box);
		if (occluded) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.ENTITIES_CULLED);
			cir.setReturnValue(false);
		}
	}

	@Unique
	private boolean isExemptFromCulling(Entity entity) {
		if (entity instanceof Player
			|| entity instanceof EnderDragon
			|| entity instanceof WitherBoss
			|| entity instanceof Display
			|| entity instanceof LightningBolt
			|| entity instanceof EndCrystal
			|| entity instanceof Guardian
		) {
			return true;
		}
		if (entity.isCurrentlyGlowing() || entity.hasCustomName() || entity.isPassenger() || entity.isVehicle()) {
			return true;
		}
		if (entity instanceof Leashable leashable && leashable.isLeashed()) {
			return true;
		}
		if (this.minecraft.player != null && entity.hasIndirectPassenger(this.minecraft.player)) {
			return true;
		}
		return false;
	}
}
