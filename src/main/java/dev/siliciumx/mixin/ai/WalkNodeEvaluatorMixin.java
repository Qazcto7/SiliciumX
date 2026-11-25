package dev.siliciumx.mixin.ai;

import dev.siliciumx.metrics.SiliciumXMetrics;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates three high-frequency allocation sources inside {@link WalkNodeEvaluator}:
 * <ol>
 *   <li>Capturing lambda allocated on every {@code getCachedPathType} lookup (even on cache hits).</li>
 *   <li>Capturing lambda allocated on every {@code hasCollisions} AABB lookup.</li>
 *   <li>{@code EnumSet<PathType>} + {@code Iterator} allocated on every {@code getPathTypeOfMob} call,
 *       replaced with a zero-allocation 32-bit {@code int} ordinal bitmask that preserves 100% identical
 *       Vanilla {@code EnumSet} iteration order and tie-breaking semantics.</li>
 * </ol>
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
	@Unique
	private static final PathType[] SILICIUMX$PATH_TYPES = PathType.values();
	@Unique
	private static final int SILICIUMX$FENCE_BIT = 1 << PathType.FENCE.ordinal();
	@Unique
	private static final int SILICIUMX$UNPASSABLE_RAIL_BIT = 1 << PathType.UNPASSABLE_RAIL.ordinal();

	@Shadow
	@Final
	private Long2ObjectMap<PathType> pathTypesByPosCacheByMob;

	@Shadow
	@Final
	private Object2BooleanMap<AABB> collisionCache;

	@Inject(method = "getCachedPathType(III)Lnet/minecraft/world/level/pathfinder/PathType;", at = @At("HEAD"), cancellable = true)
	private void siliciumx$zeroAllocGetCachedPathType(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
		long key = BlockPos.asLong(x, y, z);
		PathType cached = this.pathTypesByPosCacheByMob.get(key);
		if (cached == null) {
			cached = this.getPathTypeOfMob(this.currentContext, x, y, z, this.mob);
			this.pathTypesByPosCacheByMob.put(key, cached);
		}
		SiliciumXMetrics.increment(SiliciumXMetrics.Counter.PATHFIND_ALLOCS_AVOIDED);
		cir.setReturnValue(cached);
	}

	@Inject(method = "hasCollisions(Lnet/minecraft/world/phys/AABB;)Z", at = @At("HEAD"), cancellable = true)
	private void siliciumx$zeroAllocHasCollisions(AABB aabb, CallbackInfoReturnable<Boolean> cir) {
		if (this.collisionCache.containsKey(aabb)) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.PATHFIND_ALLOCS_AVOIDED);
			cir.setReturnValue(this.collisionCache.getBoolean(aabb));
			return;
		}
		boolean collided = !this.currentContext.level().noCollision(this.mob, aabb);
		this.collisionCache.put(aabb, collided);
		SiliciumXMetrics.increment(SiliciumXMetrics.Counter.PATHFIND_ALLOCS_AVOIDED);
		cir.setReturnValue(collided);
	}

	@Inject(
		method = "getPathTypeOfMob(Lnet/minecraft/world/level/pathfinder/PathfindingContext;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/PathType;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$zeroAllocGetPathTypeOfMob(
		PathfindingContext context,
		int x,
		int y,
		int z,
		Mob mob,
		CallbackInfoReturnable<PathType> cir
	) {
		// Guard against hypothetical modded PathType enums exceeding 32 entries.
		if (SILICIUMX$PATH_TYPES.length > 32) {
			return;
		}

		Mob activeMob = this.mob != null ? this.mob : mob;
		BlockPos mobPos = activeMob.blockPosition();
		boolean canPassDoors = this.canPassDoors();
		boolean canOpenDoors = this.canOpenDoors();
		int railChecked = 0; // 0 = unknown, 1 = on rail, 2 = not on rail

		int typeMask = 0;
		PathType lastAdded = null;
		PathType originType = null;

		for (int dx = 0; dx < this.entityWidth; dx++) {
			for (int dy = 0; dy < this.entityHeight; dy++) {
				for (int dz = 0; dz < this.entityDepth; dz++) {
					PathType type = this.getPathType(context, x + dx, y + dy, z + dz);
					if (dx == 0 && dy == 0 && dz == 0) {
						originType = type;
					}
					if (type == PathType.DOOR_WOOD_CLOSED && canOpenDoors && canPassDoors) {
						type = PathType.WALKABLE_DOOR;
					}
					if (type == PathType.DOOR_OPEN && !canPassDoors) {
						type = PathType.BLOCKED;
					}
					if (type == PathType.RAIL) {
						if (railChecked == 0) {
							boolean onRail = this.getPathType(context, mobPos.getX(), mobPos.getY(), mobPos.getZ()) == PathType.RAIL
								|| this.getPathType(context, mobPos.getX(), mobPos.getY() - 1, mobPos.getZ()) == PathType.RAIL;
							railChecked = onRail ? 1 : 2;
						}
						if (railChecked == 2) {
							type = PathType.UNPASSABLE_RAIL;
						}
					}
					typeMask |= (1 << type.ordinal());
					lastAdded = type;
				}
			}
		}

		SiliciumXMetrics.increment(SiliciumXMetrics.Counter.PATHFIND_ALLOCS_AVOIDED);

		if (typeMask == 0) {
			return;
		}

		// Fast path: single unique PathType across the mob's bounding box (common case).
		if ((typeMask & (typeMask - 1)) == 0) {
			cir.setReturnValue(lastAdded);
			return;
		}
		if ((typeMask & SILICIUMX$FENCE_BIT) != 0) {
			cir.setReturnValue(PathType.FENCE);
			return;
		}
		if ((typeMask & SILICIUMX$UNPASSABLE_RAIL_BIT) != 0) {
			cir.setReturnValue(PathType.UNPASSABLE_RAIL);
			return;
		}

		PathType bestType = PathType.BLOCKED;
		float bestMalus = mob.getPathfindingMalus(bestType);

		// Iterate set bits in ascending ordinal order — exact match to EnumSet.iterator() order!
		for (int m = typeMask; m != 0; m &= (m - 1)) {
			PathType candidate = SILICIUMX$PATH_TYPES[Integer.numberOfTrailingZeros(m)];
			float malus = mob.getPathfindingMalus(candidate);
			if (malus < 0.0f) {
				cir.setReturnValue(candidate);
				return;
			}
			if (malus >= bestMalus) {
				bestMalus = malus;
				bestType = candidate;
			}
		}

		if (originType == null) {
			originType = this.getPathType(context, x, y, z);
		}
		if (this.entityWidth > 1) {
			if (mob.getPathfindingMalus(originType) < bestMalus
				&& mob.getPathfindingMalus(PathType.BIG_MOBS_CLOSE_TO_DANGER) < bestMalus) {
				cir.setReturnValue(PathType.BIG_MOBS_CLOSE_TO_DANGER);
				return;
			}
			cir.setReturnValue(bestType);
			return;
		}

		if (originType == PathType.OPEN && bestType != PathType.OPEN && bestMalus == 0.0f) {
			cir.setReturnValue(PathType.OPEN);
			return;
		}
		cir.setReturnValue(bestType);
	}
}
