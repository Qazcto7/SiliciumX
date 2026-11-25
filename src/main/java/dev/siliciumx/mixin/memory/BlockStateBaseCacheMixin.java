package dev.siliciumx.mixin.memory;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.siliciumx.memory.BlockStateCacheDeduplicator;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deduplicates immutable {@code BlockStateBase$Cache} objects and non-trivial
 * {@code occlusionShapesByFace} arrays during {@code BlockStateBase.initCache()}.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseCacheMixin {
	@Shadow
	@Final
	private static VoxelShape[] EMPTY_OCCLUSION_SHAPES;

	@Shadow
	@Final
	private static VoxelShape[] FULL_BLOCK_OCCLUSION_SHAPES;

	@Shadow
	private VoxelShape[] occlusionShapesByFace;

	@ModifyExpressionValue(
		method = "initCache()V",
		at = @At(
			value = "NEW",
			target = "(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache;"
		)
	)
	@Coerce
	private Object siliciumx$deduplicateStateCache(@Coerce Object created) {
		return BlockStateCacheDeduplicator.deduplicateCache(created);
	}

	@Inject(method = "initCache()V", at = @At("TAIL"))
	private void siliciumx$deduplicateFaceOcclusionArrays(CallbackInfo ci) {
		VoxelShape[] faces = this.occlusionShapesByFace;
		if (faces != null && faces != EMPTY_OCCLUSION_SHAPES && faces != FULL_BLOCK_OCCLUSION_SHAPES) {
			this.occlusionShapesByFace = BlockStateCacheDeduplicator.deduplicateFaceOcclusionArray(faces);
		}
	}
}
