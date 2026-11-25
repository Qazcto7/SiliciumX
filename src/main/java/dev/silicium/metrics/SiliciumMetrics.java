package dev.silicium.metrics;

import dev.silicium.util.BoundedStringInterner;
import dev.silicium.util.CompactSineLut;
import dev.silicium.util.FastBiomeVoronoi;
import dev.silicium.util.FastLongCache;
import dev.silicium.util.FastPerlinNoise;
import dev.silicium.util.RollingTimingWindow;
import dev.silicium.util.ShapePairCache;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, zero-allocation telemetry & microbenchmark collector for Silicium.
 *
 * <p>Tracks rolling server tick times (MSPT) and client frame times alongside per-subsystem
 * optimization counters so every module's real impact can be verified live via {@code /silicium metrics}
 * or Spark/JFR comparisons.</p>
 */
public final class SiliciumMetrics {
	private static final RollingTimingWindow SERVER_TICK_WINDOW = new RollingTimingWindow(200);
	private static final RollingTimingWindow CLIENT_FRAME_WINDOW = new RollingTimingWindow(240);
	private static final Counter[] COUNTER_VALUES = Counter.values();
	private static final LongAdder[] COUNTERS = new LongAdder[COUNTER_VALUES.length];

	static {
		for (int i = 0; i < COUNTER_VALUES.length; i++) {
			COUNTERS[i] = new LongAdder();
		}
	}

	private SiliciumMetrics() {
	}

	public static void recordServerTickNanos(long nanos) {
		SERVER_TICK_WINDOW.recordNanos(nanos);
	}

	public static void recordClientFrameNanos(long nanos) {
		CLIENT_FRAME_WINDOW.recordNanos(nanos);
	}

	public static RollingTimingWindow.Snapshot serverTickSnapshot() {
		return SERVER_TICK_WINDOW.snapshot();
	}

	public static RollingTimingWindow.Snapshot clientFrameSnapshot() {
		return CLIENT_FRAME_WINDOW.snapshot();
	}

	public static void increment(Counter counter) {
		COUNTERS[counter.ordinal()].increment();
	}

	public static void add(Counter counter, long delta) {
		if (delta > 0L) {
			COUNTERS[counter.ordinal()].add(delta);
		}
	}

	public static long get(Counter counter) {
		return COUNTERS[counter.ordinal()].sum();
	}

	public static Map<Counter, Long> snapshotCounters() {
		EnumMap<Counter, Long> snapshot = new EnumMap<>(Counter.class);
		for (Counter counter : COUNTER_VALUES) {
			snapshot.put(counter, COUNTERS[counter.ordinal()].sum());
		}
		return snapshot;
	}

	public static void reset() {
		SERVER_TICK_WINDOW.reset();
		CLIENT_FRAME_WINDOW.reset();
		for (LongAdder adder : COUNTERS) {
			adder.reset();
		}
	}

	/**
	 * Runs a quick, self-contained in-JVM microbenchmark comparing Silicium's core data structures
	 * and algorithms against Vanilla equivalents. Designed to complete in ~60-120 ms without stalling.
	 */
	public static BenchmarkReport runQuickBenchmark() {
		// 1. Biome Voronoi pruning vs full 8-corner Vanilla Voronoi
		int voronoiIters = 80_000;
		long seed = 0x5EED_CAFE_BABE_1234L;

		long sink = 0;
		long t0 = System.nanoTime();
		for (int i = 0; i < voronoiIters; i++) {
			sink += vanillaVoronoiCorner(seed, i * 3 - 10000, (i & 127) - 32, (i * 7) - 20000);
		}
		long vanillaVoronoiNs = Math.max(1L, System.nanoTime() - t0);

		t0 = System.nanoTime();
		for (int i = 0; i < voronoiIters; i++) {
			sink += FastBiomeVoronoi.selectClosestCorner(seed, i * 3 - 10000, (i & 127) - 32, (i * 7) - 20000);
		}
		long siliciumVoronoiNs = Math.max(1L, System.nanoTime() - t0);

		// 2. CompactSineLut throughput
		int sinIters = 250_000;
		float fSink = 0.0f;
		t0 = System.nanoTime();
		for (int i = 0; i < sinIters; i++) {
			fSink += CompactSineLut.sin(i * 0.0137);
		}
		long siliciumSineNs = Math.max(1L, System.nanoTime() - t0);

		// 3. ShapePairCache zero-alloc identity lookup throughput
		int shapeIters = 200_000;
		Object[] shapes = new Object[32];
		for (int i = 0; i < shapes.length; i++) {
			shapes[i] = new Object();
		}
		ShapePairCache pairCache = new ShapePairCache(512);
		for (int i = 0; i < shapes.length; i++) {
			for (int j = 0; j < shapes.length; j++) {
				pairCache.put(shapes[i], shapes[j], ((i + j) & 1) == 0);
			}
		}
		t0 = System.nanoTime();
		for (int i = 0; i < shapeIters; i++) {
			sink += pairCache.get(shapes[i & 31], shapes[(i >>> 5) & 31]);
		}
		long shapeCacheNs = Math.max(1L, System.nanoTime() - t0);

		// 4. FastLongCache lookup throughput
		int chunkIters = 200_000;
		FastLongCache<Object> chunkCache = new FastLongCache<>(128);
		Object dummy = new Object();
		for (int i = 0; i < 64; i++) {
			chunkCache.put((((long) i) << 32) | (i * 17L & 0xFFFFFFFFL), dummy);
		}
		t0 = System.nanoTime();
		for (int i = 0; i < chunkIters; i++) {
			int k = i & 63;
			if (chunkCache.get((((long) k) << 32) | (k * 17L & 0xFFFFFFFFL)) != null) {
				sink++;
			}
		}
		long chunkCacheNs = Math.max(1L, System.nanoTime() - t0);

		// 5. BoundedStringInterner deduplication throughput
		int strIters = 100_000;
		BoundedStringInterner interner = new BoundedStringInterner(2048, 48);
		String[] keys = {"id", "Name", "Properties", "Palette", "Count", "Slot", "Pos", "Motion", "Rotation", "Items", "x", "y", "z", "components", "Brain", "memories"};
		for (String k : keys) {
			interner.intern(k);
		}
		t0 = System.nanoTime();
		for (int i = 0; i < strIters; i++) {
			if (interner.intern(keys[i & 15]) != null) {
				sink++;
			}
		}
		long internerNs = Math.max(1L, System.nanoTime() - t0);

		// 6. FastPerlinNoise sampleAndLerp throughput
		int perlinIters = 200_000;
		byte[] perms = new byte[256];
		for (int i = 0; i < 256; i++) {
			perms[i] = (byte) ((i * 73 + 41) & 255);
		}
		t0 = System.nanoTime();
		for (int i = 0; i < perlinIters; i++) {
			float f = (i & 255) * 0.0039f;
			fSink += FastPerlinNoise.sampleAndLerp(perms, i & 63, (i >> 6) & 63, (i >> 12) & 63, f, f * 0.5f, 1.0f - f, f * 0.5f);
		}
		long perlinNs = Math.max(1L, System.nanoTime() - t0);

		return new BenchmarkReport(
			vanillaVoronoiNs / (double) voronoiIters,
			siliciumVoronoiNs / (double) voronoiIters,
			siliciumSineNs / (double) sinIters,
			shapeCacheNs / (double) shapeIters,
			chunkCacheNs / (double) chunkIters,
			internerNs / (double) strIters,
			perlinNs / (double) perlinIters,
			sink + (long) fSink
		);
	}

	private static int vanillaVoronoiCorner(long seed, int x, int y, int z) {
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
			boolean bx = (i & 4) == 0;
			boolean by = (i & 2) == 0;
			boolean bz = (i & 1) == 0;
			int cx = bx ? qx : qx + 1;
			int cy = by ? qy : qy + 1;
			int cz = bz ? qz : qz + 1;
			double dx = bx ? fx : fx - 1.0;
			double dy = by ? fy : fy - 1.0;
			double dz = bz ? fz : fz - 1.0;
			double dist = FastBiomeVoronoi.getFiddledDistance(seed, cx, cy, cz, dx, dy, dz);
			if (dist < bestDist) {
				bestIndex = i;
				bestDist = dist;
			}
		}
		return bestIndex;
	}

	public enum Counter {
		CHUNK_CACHE_HITS("Chunk cache hits"),
		CHUNK_CACHE_MISSES("Chunk cache misses"),
		OCCLUSION_CACHE_HITS("Block occlusion cache hits"),
		FACE_OCCLUSION_HITS("Face occlusion cache hits"),
		ATTRIBUTE_FAST_CALCS("Attribute fast-path calcs"),
		PATHFIND_ALLOCS_AVOIDED("Pathfinding allocs avoided"),
		HOPPER_EMPTY_SKIPS("Hopper empty scans skipped"),
		BLOCKSTATE_CACHES_DEDUPED("BlockState caches deduped"),
		OCCLUSION_ARRAYS_DEDUPED("Occlusion arrays deduped"),
		NBT_KEYS_DEDUPED("NBT key strings deduped"),
		NBT_STRINGS_DEDUPED("NBT StringTags deduped"),
		LIGHTMAP_PASSES_SKIPPED("GPU lightmap passes skipped"),
		PARTICLES_CULLED("Particles culled"),
		PARTICLE_SPAWNS_SKIPPED("Particle spawns skipped"),
		PARTICLE_COLLISIONS_SKIPPED("Particle collisions skipped"),
		ANIMATE_FLUID_LOOKUPS_SAVED("AnimateTick fluid lookups saved"),
		ENTITIES_CULLED("Entities occlusion-culled"),
		BLOCK_ENTITIES_CULLED("Block entities occlusion-culled"),
		ASYNC_OCCLUSION_JOBS("Async occlusion raycasts offloaded"),
		SIGN_TEXT_PASSES_SKIPPED("Sign text passes skipped"),
		ENTITY_SHADOWS_OPTIMIZED("Entity shadows optimized"),
		WEATHER_COLUMNS_SKIPPED("Weather columns cached/culled"),
		DEBUG_HUD_REBUILDS_SKIPPED("F3 HUD rebuilds skipped"),
		TEXT_WIDTH_HITS("Text width cache hits"),
		RICH_TEXT_WIDTH_HITS("Rich text width cache hits"),
		SOUNDS_CULLED("Inaudible/spam sounds culled");

		private final String label;

		Counter(String label) {
			this.label = label;
		}

		public String label() {
			return this.label;
		}
	}

	public record BenchmarkReport(
		double vanillaVoronoiNsPerOp,
		double siliciumVoronoiNsPerOp,
		double siliciumSineNsPerOp,
		double shapeCacheNsPerOp,
		double chunkCacheNsPerOp,
		double internerNsPerOp,
		double siliciumPerlinNsPerOp,
		long antiDceChecksum
	) {
		public double voronoiSpeedup() {
			return this.siliciumVoronoiNsPerOp > 0.0
				? this.vanillaVoronoiNsPerOp / this.siliciumVoronoiNsPerOp
				: 1.0;
		}
	}
}
