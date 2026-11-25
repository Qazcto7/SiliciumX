package dev.silicium.mixin.occlusion;

import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.ShapePairCache;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches {@link Shapes#faceShapeOccludes(VoxelShape, VoxelShape)} results in a zero-allocation
 * ThreadLocal 512-slot identity cache, avoiding repeated {@code joinUnoptimized} temporary shape
 * allocations during lighting, fluid flow, and block support queries.
 */
@Mixin(Shapes.class)
public abstract class ShapesFaceOcclusionMixin {
	@Unique
	private static final ThreadLocal<ShapePairCache> SILICIUM$FACE_OCCLUSION_CACHE =
		ThreadLocal.withInitial(() -> new ShapePairCache(512));

	@Inject(
		method = "faceShapeOccludes(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void silicium$cachedFaceShapeOccludes(
		VoxelShape first,
		VoxelShape second,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (first == Shapes.block() || second == Shapes.block()) {
			cir.setReturnValue(true);
			return;
		}
		if (first.isEmpty() && second.isEmpty()) {
			cir.setReturnValue(false);
			return;
		}

		ShapePairCache cache = SILICIUM$FACE_OCCLUSION_CACHE.get();
		byte cached = cache.get(first, second);
		if (cached != ShapePairCache.MISS) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.FACE_OCCLUSION_HITS);
			cir.setReturnValue(cached != ShapePairCache.FALSE);
			return;
		}

		boolean result = !Shapes.joinIsNotEmpty(
			Shapes.block(),
			Shapes.joinUnoptimized(first, second, BooleanOp.OR),
			BooleanOp.ONLY_FIRST
		);
		cache.put(first, second, result);
		cir.setReturnValue(result);
	}
}
