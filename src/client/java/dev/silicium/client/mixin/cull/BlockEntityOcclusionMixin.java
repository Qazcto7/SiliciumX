package dev.silicium.client.mixin.cull;

import dev.silicium.client.cull.AsyncOcclusionEngine;
import dev.silicium.client.cull.OcclusionRaycaster;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.module.SiliciumModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Conservative, Sodium-friendly 9-point solid-block occlusion culling for block entities (chests,
 * signs, banners, beds, campfires, shulker boxes, etc.) inside {@link BlockEntityRenderDispatcher}.
 *
 * <p>Injects right before {@code BlockEntityRenderer.createRenderState()} so block entities without
 * a renderer, global off-screen renderers (Beacons, End Gateways, Structure Blocks), and out-of-range
 * block entities are already filtered out by Vanilla first.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityOcclusionMixin {
	@Shadow
	private Vec3 cameraPos;

	@Unique
	private static final boolean SILICIUM$USE_ASYNC =
		ModCompatibility.get().isModuleActive(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER);

	// Keep @Unique instance fields uninitialized so Mixin never depends on LineNumberTable initializer slicing.
	@Unique
	private AsyncOcclusionEngine silicium$asyncRaycaster;

	@Unique
	private OcclusionRaycaster silicium$beRaycaster;

	@Unique
	private Level silicium$lastLevel;

	@Inject(
		method = "<init>(Lnet/minecraft/client/gui/Font;Ljava/util/function/Supplier;Lnet/minecraft/client/renderer/block/BlockModelResolver;Lnet/minecraft/client/renderer/item/ItemModelResolver;Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Lnet/minecraft/client/resources/model/sprite/SpriteGetter;Lnet/minecraft/client/renderer/PlayerSkinRenderCache;)V",
		at = @At("RETURN")
	)
	private void silicium$initRaycasters(
		Font font,
		Supplier<EntityModelSet> entityModelSet,
		BlockModelResolver blockModelResolver,
		ItemModelResolver itemModelResolver,
		EntityRenderDispatcher entityRenderDispatcher,
		SpriteGetter spriteGetter,
		PlayerSkinRenderCache playerSkinRenderCache,
		CallbackInfo ci
	) {
		this.silicium$ensureRaycasters();
	}

	@Unique
	private void silicium$ensureRaycasters() {
		if (SILICIUM$USE_ASYNC) {
			if (this.silicium$asyncRaycaster == null) {
				this.silicium$asyncRaycaster = new AsyncOcclusionEngine("BlockEntity");
			}
		} else if (this.silicium$beRaycaster == null) {
			this.silicium$beRaycaster = new OcclusionRaycaster();
		}
	}

	@Inject(
		method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;createRenderState()Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
		),
		cancellable = true
	)
	private <E extends BlockEntity, S extends BlockEntityRenderState> void silicium$cullOccludedBlockEntity(
		E blockEntity,
		float partialTick,
		ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
		boolean offScreen,
		CallbackInfoReturnable<S> cir
	) {
		// Never cull global/off-screen block entities, moving pistons, or blocks currently being broken.
		if (offScreen
			|| crumblingOverlay != null
			|| this.cameraPos == null
			|| blockEntity instanceof PistonMovingBlockEntity
		) {
			return;
		}
		Level level = blockEntity.getLevel();
		if (level == null) {
			return;
		}
		this.silicium$ensureRaycasters();
		if (level != this.silicium$lastLevel) {
			if (this.silicium$asyncRaycaster != null) {
				this.silicium$asyncRaycaster.clear(level);
			}
			if (this.silicium$beRaycaster != null) {
				this.silicium$beRaycaster.clear();
			}
			this.silicium$lastLevel = level;
		}

		BlockPos pos = blockEntity.getBlockPos();
		double camX = this.cameraPos.x();
		double camY = this.cameraPos.y();
		double camZ = this.cameraPos.z();

		double dx = (pos.getX() + 0.5) - camX;
		double dy = (pos.getY() + 0.5) - camY;
		double dz = (pos.getZ() + 0.5) - camZ;
		if ((dx * dx + dy * dy + dz * dz) < SiliciumConfig.get().blockEntityCullingMinDistanceSq()) {
			return;
		}

		int bx = pos.getX();
		int by = pos.getY();
		int bz = pos.getZ();

		// Use conservative expanded bounds for block entities whose geometry extends beyond 1x1x1.
		double minX = bx;
		double minY = by;
		double minZ = bz;
		double maxX = bx + 1.0;
		double maxY = by + 1.0;
		double maxZ = bz + 1.0;

		if (blockEntity instanceof BannerBlockEntity) {
			minY = by - 1.0;
			maxY = by + 2.0;
		} else if (blockEntity instanceof ShulkerBoxBlockEntity) {
			minX = bx - 0.5;
			minY = by - 0.5;
			minZ = bz - 0.5;
			maxX = bx + 1.5;
			maxY = by + 1.5;
			maxZ = bz + 1.5;
		} else if (blockEntity instanceof EnchantingTableBlockEntity) {
			maxY = by + 1.5;
		}

		long key = pos.asLong();
		boolean occluded = this.silicium$asyncRaycaster != null
			? this.silicium$asyncRaycaster.isBoxOccluded(level, key, camX, camY, camZ, minX, minY, minZ, maxX, maxY, maxZ)
			: this.silicium$beRaycaster.isBoxOccluded(level, key, camX, camY, camZ, minX, minY, minZ, maxX, maxY, maxZ);
		if (occluded) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.BLOCK_ENTITIES_CULLED);
			cir.setReturnValue(null);
		}
	}
}
