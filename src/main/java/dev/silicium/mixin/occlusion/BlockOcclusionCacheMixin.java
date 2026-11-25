package dev.silicium.mixin.occlusion;

import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.ShapePairCache;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates per-call {@code Block$ShapePairKey} heap allocations and linked-map pointer chasing in
 * {@link Block#shouldRenderFace(BlockState, BlockState, Direction)} via a zero-allocation
 * ThreadLocal 512-slot 2-way associative identity cache.
 */
@Mixin(Block.class)
public abstract class BlockOcclusionCacheMixin {
	@Unique
	private static final ThreadLocal<ShapePairCache> SILICIUM$OCCLUSION_CACHE =
		ThreadLocal.withInitial(() -> new ShapePairCache(512));

	@Inject(
		method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void silicium$zeroAllocShouldRenderFace(
		BlockState state,
		BlockState neighborState,
		Direction direction,
		CallbackInfoReturnable<Boolean> cir
	) {
		VoxelShape neighborFace = neighborState.getFaceOcclusionShape(direction.getOpposite());
		if (neighborFace == Shapes.block()) {
			cir.setReturnValue(false);
			return;
		}
		if (state.skipRendering(neighborState, direction)) {
			cir.setReturnValue(false);
			return;
		}
		if (neighborFace == Shapes.empty()) {
			cir.setReturnValue(true);
			return;
		}
		VoxelShape selfFace = state.getFaceOcclusionShape(direction);
		if (selfFace == Shapes.empty()) {
			cir.setReturnValue(true);
			return;
		}

		ShapePairCache cache = SILICIUM$OCCLUSION_CACHE.get();
		byte cached = cache.get(selfFace, neighborFace);
		if (cached != ShapePairCache.MISS) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.OCCLUSION_CACHE_HITS);
			cir.setReturnValue(cached != ShapePairCache.FALSE);
			return;
		}

		boolean result = Shapes.joinIsNotEmpty(selfFace, neighborFace, BooleanOp.ONLY_FIRST);
		cache.put(selfFace, neighborFace, result);
		cir.setReturnValue(result);
	}
}
