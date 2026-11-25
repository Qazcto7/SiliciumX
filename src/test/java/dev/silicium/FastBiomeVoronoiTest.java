package dev.silicium;

import dev.silicium.util.FastBiomeVoronoi;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastBiomeVoronoiTest {
	/**
	 * Exact reference replica of Vanilla Minecraft 26.3's {@code BiomeManager.getBiome(int, int, int)}
	 * and {@code getFiddledDistance} / {@code getFiddle} logic.
	 */
	private static int referenceVanillaCorner(long seed, int x, int y, int z) {
		int sx = x - 2;
		int sy = y - 2;
		int sz = z - 2;
		int qx = sx >> 2;
		int qy = sy >> 2;
		int qz = sz >> 2;
		double fx = (sx & 3) / 4.0;
		double fy = (sy & 3) / 4.0;
		double fz = (sz & 3) / 4.0;

		int bestIndex = 0;
		double bestDist = Double.POSITIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			boolean xZero = (i & 4) == 0;
			boolean yZero = (i & 2) == 0;
			boolean zZero = (i & 1) == 0;
			int cx = xZero ? qx : qx + 1;
			int cy = yZero ? qy : qy + 1;
			int cz = zZero ? qz : qz + 1;
			double dx = xZero ? fx : fx - 1.0;
			double dy = yZero ? fy : fy - 1.0;
			double dz = zZero ? fz : fz - 1.0;
			double dist = referenceFiddledDistance(seed, cx, cy, cz, dx, dy, dz);
			if (bestDist > dist) {
				bestIndex = i;
				bestDist = dist;
			}
		}
		return bestIndex;
	}

	private static double referenceFiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
		long s = lcg(seed, x);
		s = lcg(s, y);
		s = lcg(s, z);
		s = lcg(s, x);
		s = lcg(s, y);
		s = lcg(s, z);
		double fx = referenceFiddle(s);
		s = lcg(s, seed);
		double fy = referenceFiddle(s);
		s = lcg(s, seed);
		double fz = referenceFiddle(s);
		return sq(dz + fz) + sq(dy + fy) + sq(dx + fx);
	}

	private static long lcg(long seed, long salt) {
		seed *= seed * 6364136223846793005L + 1442695040888963407L;
		return seed + salt;
	}

	private static double referenceFiddle(long seed) {
		double d = Math.floorMod(seed >> 24, 1024) / 1024.0;
		return (d - 0.5) * 0.9;
	}

	private static double sq(double v) {
		return v * v;
	}

	@Test
	void bitIdenticalToVanillaAcross200000RandomAndEdgeCoordinates() {
		long[] edgeSeeds = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0xCAFEBABE12345678L, -987654321012345L};
		for (long seed : edgeSeeds) {
			for (int x = -16; x <= 16; x++) {
				for (int y = -8; y <= 8; y++) {
					for (int z = -16; z <= 16; z += 4) {
						final int fx = x, fy = y, fz = z;
						int expected = referenceVanillaCorner(seed, x, y, z);
						int actual = FastBiomeVoronoi.selectClosestCorner(seed, x, y, z);
						assertEquals(
							expected,
							actual,
							() -> "Mismatch for seed=" + seed + " pos=(" + fx + "," + fy + "," + fz + ")"
						);
					}
				}
			}
		}

		Random rng = new Random(26032026L);
		for (int i = 0; i < 200_000; i++) {
			final int iter = i;
			long seed = rng.nextLong();
			int x = rng.nextInt(60_000_000) - 30_000_000;
			int y = rng.nextInt(384) - 64;
			int z = rng.nextInt(60_000_000) - 30_000_000;
			int expected = referenceVanillaCorner(seed, x, y, z);
			int actual = FastBiomeVoronoi.selectClosestCorner(seed, x, y, z);
			assertEquals(
				expected,
				actual,
				() -> "Mismatch at iter=" + iter + " seed=" + seed + " pos=(" + x + "," + y + "," + z + ")"
			);
		}
	}
}
