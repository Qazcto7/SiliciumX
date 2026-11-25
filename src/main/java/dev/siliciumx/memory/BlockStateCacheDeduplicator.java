package dev.siliciumx.memory;

import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.mixin.memory.BlockStateCacheAccessor;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical deduplication store for immutable {@code BlockBehaviour$BlockStateBase$Cache} instances
 * and per-face {@code VoxelShape[6]} occlusion arrays across all registered {@code BlockState}s.
 *
 * <p>Lives outside {@code dev.siliciumx.mixin.*} so Mixin classloading rules permit direct calls
 * from transformed Minecraft classes.</p>
 */
public final class BlockStateCacheDeduplicator {
	private static final ConcurrentHashMap<CacheKey, Object> CANONICAL_CACHES = new ConcurrentHashMap<>(512);
	private static final ConcurrentHashMap<FaceShapesKey, VoxelShape[]> CANONICAL_FACE_ARRAYS = new ConcurrentHashMap<>(256);

	private BlockStateCacheDeduplicator() {
	}

	public static Object deduplicateCache(Object candidate) {
		if (!(candidate instanceof BlockStateCacheAccessor accessor)) {
			return candidate;
		}
		boolean[] sturdy = accessor.siliciumx$getFaceSturdy();
		if (sturdy != null && sturdy.length > 64) {
			return candidate;
		}
		long sturdyMask = packFaceSturdyMask(sturdy);
		CacheKey key = new CacheKey(
			accessor.siliciumx$getCollisionShape(),
			accessor.siliciumx$isLargeCollisionShape(),
			accessor.siliciumx$isCollisionShapeFullBlock(),
			sturdyMask
		);
		Object existing = CANONICAL_CACHES.putIfAbsent(key, candidate);
		if (existing != null) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.BLOCKSTATE_CACHES_DEDUPED);
			return existing;
		}
		return candidate;
	}

	public static VoxelShape[] deduplicateFaceOcclusionArray(VoxelShape[] shapes) {
		if (shapes == null || shapes.length != 6) {
			return shapes;
		}
		FaceShapesKey key = new FaceShapesKey(shapes);
		VoxelShape[] existing = CANONICAL_FACE_ARRAYS.putIfAbsent(key, shapes);
		if (existing != null) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.OCCLUSION_ARRAYS_DEDUPED);
			return existing;
		}
		return shapes;
	}

	private static long packFaceSturdyMask(boolean[] sturdy) {
		if (sturdy == null) {
			return 0L;
		}
		long mask = 0L;
		for (int i = 0; i < sturdy.length; i++) {
			if (sturdy[i]) {
				mask |= (1L << i);
			}
		}
		return mask;
	}

	private static final class CacheKey {
		private final VoxelShape collisionShape;
		private final boolean largeCollisionShape;
		private final boolean fullBlock;
		private final long faceSturdyMask;
		private final int hash;

		CacheKey(VoxelShape collisionShape, boolean largeCollisionShape, boolean fullBlock, long faceSturdyMask) {
			this.collisionShape = collisionShape;
			this.largeCollisionShape = largeCollisionShape;
			this.fullBlock = fullBlock;
			this.faceSturdyMask = faceSturdyMask;
			int h = System.identityHashCode(collisionShape);
			h = 31 * h + (largeCollisionShape ? 1 : 0);
			h = 31 * h + (fullBlock ? 1 : 0);
			h = 31 * h + Long.hashCode(faceSturdyMask);
			this.hash = h;
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof CacheKey other)) {
				return false;
			}
			return this.faceSturdyMask == other.faceSturdyMask
				&& this.largeCollisionShape == other.largeCollisionShape
				&& this.fullBlock == other.fullBlock
				&& this.collisionShape == other.collisionShape;
		}
	}

	private static final class FaceShapesKey {
		private final VoxelShape[] shapes;
		private final int hash;

		FaceShapesKey(VoxelShape[] shapes) {
			this.shapes = shapes;
			int h = 1;
			for (VoxelShape s : shapes) {
				h = 31 * h + System.identityHashCode(s);
			}
			this.hash = h;
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof FaceShapesKey other)) {
				return false;
			}
			VoxelShape[] a = this.shapes;
			VoxelShape[] b = other.shapes;
			if (a.length != b.length) {
				return false;
			}
			for (int i = 0; i < a.length; i++) {
				if (a[i] != b[i]) {
					return false;
				}
			}
			return true;
		}
	}
}
