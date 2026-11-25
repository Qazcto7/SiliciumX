package dev.silicium.util;

/**
 * Exact, mathematically bit-identical accelerator for Minecraft's 3D Voronoi biome sampling
 * ({@code BiomeManager.getBiome(int, int, int)}).
 *
 * <p>Key optimizations:</p>
 * <ol>
 *   <li>Replaces {@code Math.floorMod(seed >> 24, 1024)} with bitwise {@code ((int) (seed >> 24)) & 1023}
 *       (exact equivalence for power-of-two modulus 1024).</li>
 *   <li>Evaluates the geometrically nearest grid corner first to establish a tight upper bound.</li>
 *   <li>Before running the 8 LCG multiplications for each remaining corner, computes an exact
 *       mathematical lower bound using the known fiddle interval {@code [-0.45, +0.44912109375]}:
 *       any corner whose minimum possible squared distance is {@code >= bestDistance} is proven
 *       incapable of winning and is pruned immediately.</li>
 * </ol>
 *
 * <p>Produces bit-for-bit identical winning corner indices to Vanilla's {@code BiomeManager}
 * for all possible seeds and block coordinates.</p>
 */
public final class FastBiomeVoronoi {
	private static final long LCG_MULTIPLIER = 6364136223846793005L;
	private static final long LCG_INCREMENT = 1442695040888963407L;
	private static final double MAX_FIDDLE_ABS = 0.45;
	// Conservative ULP margin so strict floating-point rounding never falsely prunes a tie-breaker.
	private static final double PRUNE_EPSILON = 1e-12;

	private FastBiomeVoronoi() {
	}

	/**
	 * Computes the winning Voronoi corner bitmask (0..7, matching Vanilla's {@code bestIndex})
	 * where bit 2 = +X, bit 1 = +Y, bit 0 = +Z relative to {@code (quartX, quartY, quartZ)}.
	 */
	public static int selectClosestCorner(long biomeZoomSeed, int blockX, int blockY, int blockZ) {
		int shiftedX = blockX - 2;
		int shiftedY = blockY - 2;
		int shiftedZ = blockZ - 2;

		int quartX = shiftedX >> 2;
		int quartY = shiftedY >> 2;
		int quartZ = shiftedZ >> 2;

		double fracX = (shiftedX & 3) * 0.25;
		double fracY = (shiftedY & 3) * 0.25;
		double fracZ = (shiftedZ & 3) * 0.25;

		// Precompute exact lower-bound squared axis distance for each of the two choices (0 or 1) per axis.
		double minSqX0 = axisMinDistSq(fracX);
		double minSqX1 = axisMinDistSq(fracX - 1.0);
		double minSqY0 = axisMinDistSq(fracY);
		double minSqY1 = axisMinDistSq(fracY - 1.0);
		double minSqZ0 = axisMinDistSq(fracZ);
		double minSqZ1 = axisMinDistSq(fracZ - 1.0);

		// Evaluate the geometrically closest corner first to establish a tight upper bound immediately.
		int firstCorner = ((fracX >= 0.5) ? 4 : 0) | ((fracY >= 0.5) ? 2 : 0) | ((fracZ >= 0.5) ? 1 : 0);
		double bestDist = evaluateCorner(
			biomeZoomSeed,
			quartX, quartY, quartZ,
			fracX, fracY, fracZ,
			firstCorner
		);
		int bestCorner = firstCorner;

		for (int corner = 0; corner < 8; corner++) {
			if (corner == firstCorner) {
				continue;
			}
			boolean xBit = (corner & 4) != 0;
			boolean yBit = (corner & 2) != 0;
			boolean zBit = (corner & 1) != 0;

			double lowerBound = (xBit ? minSqX1 : minSqX0)
				+ (yBit ? minSqY1 : minSqY0)
				+ (zBit ? minSqZ1 : minSqZ0);

			// Strict pruning: if even the minimum theoretically achievable distance exceeds bestDist,
			// this corner can never beat bestCorner.
			if (lowerBound > bestDist + PRUNE_EPSILON) {
				continue;
			}

			double dist = evaluateCorner(
				biomeZoomSeed,
				quartX, quartY, quartZ,
				fracX, fracY, fracZ,
				corner
			);

			// Exact Vanilla tie-breaking: Vanilla iterates 0..7 and updates strictly on (dist < bestDist),
			// meaning lower corner index wins exact floating-point ties.
			if (dist < bestDist || (dist == bestDist && corner < bestCorner)) {
				bestDist = dist;
				bestCorner = corner;
			}
		}

		return bestCorner;
	}

	private static double axisMinDistSq(double delta) {
		double abs = Math.abs(delta) - MAX_FIDDLE_ABS;
		return abs > 0.0 ? abs * abs : 0.0;
	}

	private static double evaluateCorner(
		long biomeZoomSeed,
		int quartX, int quartY, int quartZ,
		double fracX, double fracY, double fracZ,
		int corner
	) {
		boolean xZero = (corner & 4) == 0;
		boolean yZero = (corner & 2) == 0;
		boolean zZero = (corner & 1) == 0;

		int cx = xZero ? quartX : quartX + 1;
		int cy = yZero ? quartY : quartY + 1;
		int cz = zZero ? quartZ : quartZ + 1;

		double dx = xZero ? fracX : fracX - 1.0;
		double dy = yZero ? fracY : fracY - 1.0;
		double dz = zZero ? fracZ : fracZ - 1.0;

		return getFiddledDistance(biomeZoomSeed, cx, cy, cz, dx, dy, dz);
	}

	public static double getFiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
		long s = lcgNext(seed, x);
		s = lcgNext(s, y);
		s = lcgNext(s, z);
		s = lcgNext(s, x);
		s = lcgNext(s, y);
		s = lcgNext(s, z);
		double fx = getFiddle(s);
		s = lcgNext(s, seed);
		double fy = getFiddle(s);
		s = lcgNext(s, seed);
		double fz = getFiddle(s);

		double tz = dz + fz;
		double ty = dy + fy;
		double tx = dx + fx;
		return tz * tz + ty * ty + tx * tx;
	}

	private static long lcgNext(long seed, long salt) {
		seed *= seed * LCG_MULTIPLIER + LCG_INCREMENT;
		return seed + salt;
	}

	private static double getFiddle(long seed) {
		// Exact equivalent of ((Math.floorMod(seed >> 24, 1024) / 1024.0) - 0.5) * 0.9
		double frac = (((int) (seed >> 24)) & 1023) / 1024.0;
		return (frac - 0.5) * 0.9;
	}
}
