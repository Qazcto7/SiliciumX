package dev.silicium.client.cull;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.config.SiliciumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;

/**
 * Conservative, zero-allocation 3D Amanatides-Woo DDA solid-block occlusion engine with a short-TTL
 * primitive visibility cache.
 *
 * <p>Visual faithfulness guarantee: an entity or block-entity AABB is marked occluded ONLY when
 * EVERY single sample point (center + all 8 expanded AABB corners = 9 rays) is blocked by a full
 * opaque {@link BlockState#isSolidRender()} block strictly between the camera and the target voxel.
 * If even one corner is visible around a doorway, slab, fence, glass pane, or edge, the target is
 * immediately rendered with zero pop-in.</p>
 *
 * <p>Provides both a synchronous per-renderer instance and a stateless, thread-safe traversal entry
 * point ({@link #isBoxOccludedStateless}) used by {@link AsyncOcclusionEngine}.</p>
 */
public final class OcclusionRaycaster {
	private static final int CACHE_SIZE = 1024;
	private static final int CACHE_MASK = CACHE_SIZE - 1;
	private static final double CAMERA_MOVE_INVALIDATE_SQ = 0.0625; // 0.25^2 blocks

	private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

	// Primitive flat cache: key -> (frameStamp << 1 | visibleBit)
	private final long[] cacheKeys = new long[CACHE_SIZE];
	private final long[] cacheValues = new long[CACHE_SIZE];
	private final boolean[] cacheOccupied = new boolean[CACHE_SIZE];

	private double lastCamX = Double.NaN;
	private double lastCamY = Double.NaN;
	private double lastCamZ = Double.NaN;

	/**
	 * Tests whether an entity/block-entity AABB is completely hidden behind opaque solid blocks
	 * relative to the camera.
	 *
	 * @param cacheKey unique stable key (e.g. entity ID or {@code BlockPos.asLong()})
	 * @return {@code true} if guaranteed fully occluded; {@code false} if potentially visible
	 */
	public boolean isOccluded(
		BlockGetter level,
		long cacheKey,
		double camX,
		double camY,
		double camZ,
		AABB box
	) {
		return isBoxOccluded(
			level,
			cacheKey,
			camX, camY, camZ,
			box.minX, box.minY, box.minZ,
			box.maxX, box.maxY, box.maxZ
		);
	}

	/**
	 * Allocation-free overload for axis-aligned bounds (e.g. block entities) without constructing an {@link AABB}.
	 */
	public boolean isBoxOccluded(
		BlockGetter level,
		long cacheKey,
		double camX,
		double camY,
		double camZ,
		double boxMinX,
		double boxMinY,
		double boxMinZ,
		double boxMaxX,
		double boxMaxY,
		double boxMaxZ
	) {
		double dxCam = camX - this.lastCamX;
		double dyCam = camY - this.lastCamY;
		double dzCam = camZ - this.lastCamZ;
		if (Double.isNaN(this.lastCamX) || (dxCam * dxCam + dyCam * dyCam + dzCam * dzCam) > CAMERA_MOVE_INVALIDATE_SQ) {
			Arrays.fill(this.cacheOccupied, false);
			this.lastCamX = camX;
			this.lastCamY = camY;
			this.lastCamZ = camZ;
		}

		long frame = SiliciumClientState.frameCounter();
		SiliciumConfig cfg = SiliciumConfig.get();
		int ttl = cfg.occlusionCacheFrames;
		int slot = mixKey(cacheKey) & CACHE_MASK;

		if (this.cacheOccupied[slot] && this.cacheKeys[slot] == cacheKey) {
			long packed = this.cacheValues[slot];
			long cachedFrame = packed >>> 1;
			if (frame - cachedFrame < ttl) {
				return (packed & 1L) == 1L;
			}
		}

		boolean occluded = isBoxOccludedStateless(
			level,
			this.mutablePos,
			cfg.occlusionMaxRaySteps,
			camX, camY, camZ,
			boxMinX, boxMinY, boxMinZ,
			boxMaxX, boxMaxY, boxMaxZ
		);
		this.cacheKeys[slot] = cacheKey;
		this.cacheValues[slot] = (frame << 1) | (occluded ? 1L : 0L);
		this.cacheOccupied[slot] = true;
		return occluded;
	}

	public void clear() {
		Arrays.fill(this.cacheOccupied, false);
		this.lastCamX = Double.NaN;
	}

	/**
	 * Stateless, thread-safe 9-ray conservative occlusion check using caller-owned
	 * {@code scratchPos}. May safely run on background worker threads against loaded chunks;
	 * falls back conservatively to {@code false} (visible) if any accessed chunk is missing or
	 * concurrently resizing.
	 */
	public static boolean isBoxOccludedStateless(
		BlockGetter level,
		BlockPos.MutableBlockPos scratchPos,
		int maxSteps,
		double camX, double camY, double camZ,
		double boxMinX, double boxMinY, double boxMinZ,
		double boxMaxX, double boxMaxY, double boxMaxZ
	) {
		// Small safety inflation so entities/blocks near edges never suffer corner pop-in.
		double pad = 0.15;
		double minX = boxMinX - pad;
		double minY = boxMinY - pad;
		double minZ = boxMinZ - pad;
		double maxX = boxMaxX + pad;
		double maxY = boxMaxY + pad;
		double maxZ = boxMaxZ + pad;

		// Never cull if camera is inside or touching the expanded AABB.
		if (camX >= minX && camX <= maxX && camY >= minY && camY <= maxY && camZ >= minZ && camZ <= maxZ) {
			return false;
		}

		Level world = level instanceof Level w ? w : null;

		// Quick reject: if the camera itself is inside a solid block (spectator / suffocating), do not cull.
		int camBx = Mth.floor(camX);
		int camBy = Mth.floor(camY);
		int camBz = Mth.floor(camZ);
		if (isSolidBlockAt(world, level, scratchPos, camBx, camBy, camBz)) {
			return false;
		}

		// Check center first: visible entities almost always exit on this very first ray!
		double cx = (minX + maxX) * 0.5;
		double cy = (minY + maxY) * 0.5;
		double cz = (minZ + maxZ) * 0.5;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, cx, cy, cz, maxSteps)) {
			return false;
		}

		// All 8 corners must ALSO be occluded to cull safely without any pop-in.
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, minX, minY, minZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, maxX, minY, minZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, minX, maxY, minZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, maxX, maxY, minZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, minX, minY, maxZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, maxX, minY, maxZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, minX, maxY, maxZ, maxSteps)) return false;
		if (!isRayBlockedBySolidBlock(world, level, scratchPos, camX, camY, camZ, maxX, maxY, maxZ, maxSteps)) return false;

		return true;
	}

	private static boolean isSolidBlockAt(
		Level world,
		BlockGetter fallbackGetter,
		BlockPos.MutableBlockPos scratchPos,
		int bx,
		int by,
		int bz
	) {
		try {
			scratchPos.set(bx, by, bz);
			if (world != null) {
				ChunkAccess chunk = world.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false);
				if (chunk == null) {
					return false;
				}
				BlockState state = chunk.getBlockState(scratchPos);
				return state != null && state.isSolidRender();
			}
			BlockState state = fallbackGetter.getBlockState(scratchPos);
			return state != null && state.isSolidRender();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	/**
	 * Zero-allocation 3D DDA traversal from camera to target point with cached chunk reuse across
	 * consecutive steps. Returns {@code true} ONLY if an intermediate voxel strictly before the target
	 * voxel has {@link BlockState#isSolidRender()} == {@code true}.
	 */
	private static boolean isRayBlockedBySolidBlock(
		Level world,
		BlockGetter fallbackGetter,
		BlockPos.MutableBlockPos scratchPos,
		double startX, double startY, double startZ,
		double endX, double endY, double endZ,
		int maxSteps
	) {
		int x = Mth.floor(startX);
		int y = Mth.floor(startY);
		int z = Mth.floor(startZ);

		int endBx = Mth.floor(endX);
		int endBy = Mth.floor(endY);
		int endBz = Mth.floor(endZ);

		if (x == endBx && y == endBy && z == endBz) {
			return false;
		}

		double dx = endX - startX;
		double dy = endY - startY;
		double dz = endZ - startZ;

		int stepX = dx > 0.0 ? 1 : (dx < 0.0 ? -1 : 0);
		int stepY = dy > 0.0 ? 1 : (dy < 0.0 ? -1 : 0);
		int stepZ = dz > 0.0 ? 1 : (dz < 0.0 ? -1 : 0);

		double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
		double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
		double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

		double tMaxX = stepX > 0 ? (x + 1.0 - startX) * tDeltaX : (stepX < 0 ? (startX - x) * tDeltaX : Double.POSITIVE_INFINITY);
		double tMaxY = stepY > 0 ? (y + 1.0 - startY) * tDeltaY : (stepY < 0 ? (startY - y) * tDeltaY : Double.POSITIVE_INFINITY);
		double tMaxZ = stepZ > 0 ? (z + 1.0 - startZ) * tDeltaZ : (stepZ < 0 ? (startZ - z) * tDeltaZ : Double.POSITIVE_INFINITY);

		int cachedChunkX = Integer.MIN_VALUE;
		int cachedChunkZ = Integer.MIN_VALUE;
		ChunkAccess cachedChunk = null;

		for (int step = 0; step < maxSteps; step++) {
			double t;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) {
					t = tMaxX;
					x += stepX;
					tMaxX += tDeltaX;
				} else {
					t = tMaxZ;
					z += stepZ;
					tMaxZ += tDeltaZ;
				}
			} else {
				if (tMaxY < tMaxZ) {
					t = tMaxY;
					y += stepY;
					tMaxY += tDeltaY;
				} else {
					t = tMaxZ;
					z += stepZ;
					tMaxZ += tDeltaZ;
				}
			}

			// Reached target voxel or crossed the end of the segment (t >= 1.0) unobstructed -> visible!
			if (t >= 1.0 || (x == endBx && y == endBy && z == endBz)) {
				return false;
			}

			try {
				scratchPos.set(x, y, z);
				BlockState state;
				if (world != null) {
					int cx = x >> 4;
					int cz = z >> 4;
					if (cx != cachedChunkX || cz != cachedChunkZ) {
						cachedChunkX = cx;
						cachedChunkZ = cz;
						cachedChunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
					}
					if (cachedChunk == null) {
						return false;
					}
					state = cachedChunk.getBlockState(scratchPos);
				} else {
					state = fallbackGetter.getBlockState(scratchPos);
				}
				if (state != null && state.isSolidRender()) {
					return true;
				}
			} catch (RuntimeException ignored) {
				// Defensive guard if a chunk palette resizes concurrently on another thread -> treat visible.
				return false;
			}
		}

		// Budget reached without confirming a solid wall -> conservatively treat as visible.
		return false;
	}

	static int mixKey(long key) {
		long z = key;
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return (int) (z ^ (z >>> 31));
	}
}
