package dev.siliciumx;

import dev.siliciumx.memory.StringTagPool;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.BoundedStringInterner;
import dev.siliciumx.util.FastLongCache;
import dev.siliciumx.util.MathBits;
import dev.siliciumx.util.RichTextFloatCache;
import dev.siliciumx.util.RollingTimingWindow;
import dev.siliciumx.util.ShapePairCache;
import dev.siliciumx.util.StringFloatCache;
import dev.siliciumx.util.TickBurstCounter;
import dev.siliciumx.util.WeatherColumnCache;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataStructuresTest {
	@Test
	void mathBitsNextPowerOfTwoHandlesBoundsCleanly() {
		assertEquals(1, MathBits.nextPowerOfTwo(-5));
		assertEquals(1, MathBits.nextPowerOfTwo(0));
		assertEquals(1, MathBits.nextPowerOfTwo(1));
		assertEquals(2, MathBits.nextPowerOfTwo(2));
		assertEquals(4, MathBits.nextPowerOfTwo(3));
		assertEquals(64, MathBits.nextPowerOfTwo(64));
		assertEquals(128, MathBits.nextPowerOfTwo(65));
		assertEquals(1 << 30, MathBits.nextPowerOfTwo(Integer.MAX_VALUE));
	}

	@Test
	void fastLongCacheStoresRetrievesAndClears() {
		FastLongCache<String> cache = new FastLongCache<>(64);
		assertEquals(64, cache.capacity());

		for (int x = -4; x < 4; x++) {
			for (int z = -4; z < 4; z++) {
				long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
				cache.put(key, x + "," + z);
			}
		}

		long sampleKey = (((long) -2) << 32) | (3 & 0xFFFFFFFFL);
		assertEquals("-2,3", cache.get(sampleKey));

		cache.clear();
		assertNull(cache.get(sampleKey));
		assertNull(cache.get(0L));
	}

	@Test
	void shapePairCacheUsesStrictReferenceIdentityAndOrder() {
		ShapePairCache cache = new ShapePairCache(128);
		String a1 = new String("shapeA");
		String a2 = new String("shapeA");
		String b = new String("shapeB");

		assertEquals(ShapePairCache.MISS, cache.get(a1, b));
		cache.put(a1, b, true);
		cache.put(b, a1, false);

		assertEquals(ShapePairCache.TRUE, cache.get(a1, b));
		assertEquals(ShapePairCache.FALSE, cache.get(b, a1));
		// Reference identity check: distinct object with equal value must NOT match.
		assertEquals(ShapePairCache.MISS, cache.get(a2, b));
	}

	@Test
	void boundedStringInternerDeduplicatesShortKeysAndRespectsMaxLength() {
		BoundedStringInterner interner = new BoundedStringInterner(256, 32);
		String k1 = new String("Palette");
		String k2 = new String("Palette");
		assertNotSame(k1, k2);

		String r1 = interner.intern(k1);
		String r2 = interner.intern(k2);
		assertSame(r1, r2);
		assertNotSame(k2, r2);

		String longStr = "x".repeat(64);
		String longStr2 = "x".repeat(64);
		assertSame(longStr, interner.intern(longStr));
		assertSame(longStr2, interner.intern(longStr2));
		assertNotSame(interner.intern(longStr), interner.intern(longStr2));
	}

	@Test
	void rollingTimingWindowComputesAccuratePercentiles() {
		RollingTimingWindow window = new RollingTimingWindow(100);
		for (int i = 1; i <= 100; i++) {
			window.recordNanos(i * 1_000_000L); // 1ms .. 100ms
		}
		RollingTimingWindow.Snapshot snap = window.snapshot();
		assertEquals(100, snap.samples());
		assertEquals(50.5, snap.meanMs(), 0.001);
		assertEquals(1.0, snap.minMs(), 0.001);
		assertEquals(50.0, snap.p50Ms(), 0.001);
		assertEquals(95.0, snap.p95Ms(), 0.001);
		assertEquals(99.0, snap.p99Ms(), 0.001);
		assertEquals(100.0, snap.maxMs(), 0.001);

		window.reset();
		assertEquals(0, window.snapshot().samples());
	}

	@Test
	void rollingTimingWindowIsSafeUnderConcurrentReadsAndWrites() throws Exception {
		RollingTimingWindow window = new RollingTimingWindow(64);
		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean failed = new AtomicBoolean(false);

		Thread writer = new Thread(() -> {
			try {
				start.await();
				for (int i = 1; i <= 5_000; i++) {
					window.recordNanos(i * 10_000L);
					if ((i & 511) == 0) {
						window.reset();
					}
				}
			} catch (Throwable t) {
				failed.set(true);
			}
		});
		Thread reader = new Thread(() -> {
			try {
				start.await();
				for (int i = 0; i < 2_000; i++) {
					RollingTimingWindow.Snapshot snap = window.snapshot();
					if (snap.samples() < 0 || snap.minMs() > snap.maxMs() + 1e-6) {
						failed.set(true);
					}
				}
			} catch (Throwable t) {
				failed.set(true);
			}
		});

		writer.start();
		reader.start();
		start.countDown();
		writer.join(5_000L);
		reader.join(5_000L);
		assertFalse(failed.get());
	}

	@Test
	void stringFloatCacheStoresEvictsClearsAndEnforcesMaxLength() {
		StringFloatCache cache = new StringFloatCache(64, 32);
		assertTrue(Float.isNaN(cache.get("FPS: 240")));

		cache.put("FPS: 240", 48.5f);
		assertEquals(48.5f, cache.get(new String("FPS: 240")));

		cache.clear();
		assertTrue(Float.isNaN(cache.get("FPS: 240")));

		assertTrue(cache.isEligible("short"));
		assertFalse(cache.isEligible(""));
		assertFalse(cache.isEligible("a".repeat(33)));
	}

	@Test
	void stringTagPoolDeduplicatesShortValuesAndSkipsLongStrings() {
		StringTag a = StringTagPool.internOrCreate(new String("minecraft:stone"));
		StringTag b = StringTagPool.internOrCreate(new String("minecraft:stone"));
		assertSame(a, b);
		assertEquals("minecraft:stone", a.value());
		assertNull(StringTagPool.internOrCreate(""));
		assertNull(StringTagPool.internOrCreate("x".repeat(65)));
	}

	@Test
	void richTextFloatCacheDistinguishesStyleAndTranslatableFlags() {
		RichTextFloatCache cache = new RichTextFloatCache(64, 32);
		Object normalStyle = "style-normal";
		Object boldStyle = "style-bold";

		assertTrue(Float.isNaN(cache.get("gui.done", normalStyle, true)));

		cache.put("gui.done", normalStyle, false, 42.0f);
		cache.put("gui.done", normalStyle, true, 26.0f);
		cache.put("gui.done", boldStyle, true, 30.0f);

		assertEquals(42.0f, cache.get(new String("gui.done"), normalStyle, false));
		assertEquals(26.0f, cache.get(new String("gui.done"), normalStyle, true));
		assertEquals(30.0f, cache.get(new String("gui.done"), boldStyle, true));
		assertTrue(Float.isNaN(cache.get("gui.done", boldStyle, false)));

		cache.clear();
		assertTrue(Float.isNaN(cache.get("gui.done", normalStyle, true)));
		assertTrue(cache.isEligible("ok"));
		assertFalse(cache.isEligible(""));
		assertFalse(cache.isEligible("a".repeat(33)));
	}

	@Test
	void tickBurstCounterCapsPerTickAndResetsOnAdvance() {
		TickBurstCounter<String> counter = new TickBurstCounter<>(32);

		// Allows up to 3 identical sounds in tick 10
		assertTrue(counter.tryAcquire(10, "minecraft:entity.experience_orb.pickup", 3));
		assertTrue(counter.tryAcquire(10, "minecraft:entity.experience_orb.pickup", 3));
		assertTrue(counter.tryAcquire(10, "minecraft:entity.experience_orb.pickup", 3));
		assertFalse(counter.tryAcquire(10, "minecraft:entity.experience_orb.pickup", 3));

		// Distinct sound key in same tick is unaffected
		assertTrue(counter.tryAcquire(10, "minecraft:block.stone.break", 3));

		// Next tick resets counts automatically
		assertTrue(counter.tryAcquire(11, "minecraft:entity.experience_orb.pickup", 3));

		// Cap <= 0 disables rate limiting
		for (int i = 0; i < 10; i++) {
			assertTrue(counter.tryAcquire(11, "minecraft:entity.experience_orb.pickup", 0));
		}
	}

	@Test
	void weatherColumnCacheHonorsTtlCamYChangesAndClear() {
		WeatherColumnCache cache = new WeatherColumnCache(256);
		int x = 14;
		int z = -27;
		int slot = cache.slot(x, z);

		assertFalse(cache.isValid(slot, x, z, 64, 0L));
		cache.put(slot, x, z, 64, 72, WeatherColumnCache.PRECIP_RAIN, 0x00F00000, 10L);

		assertTrue(cache.isValid(slot, x, z, 64, 10L));
		assertTrue(cache.isValid(slot, x, z, 64, 17L));
		assertEquals(72, cache.heightAt(slot));
		assertEquals(WeatherColumnCache.PRECIP_RAIN, cache.precipAt(slot));
		assertEquals(0x00F00000, cache.lightAt(slot));

		// CamY change invalidates immediately
		assertFalse(cache.isValid(slot, x, z, 65, 10L));
		// Beyond max staggered TTL (8 + 7 = 15 frames), entry expires
		assertFalse(cache.isValid(slot, x, z, 64, 30L));

		cache.clear();
		assertFalse(cache.isValid(slot, x, z, 64, 10L));
	}

	@Test
	void builtInMicrobenchmarkCompletesCleanly() {
		SiliciumXMetrics.BenchmarkReport report = SiliciumXMetrics.runQuickBenchmark();
		assertTrue(report.vanillaVoronoiNsPerOp() > 0.0);
		assertTrue(report.siliciumxVoronoiNsPerOp() > 0.0);
		assertTrue(report.siliciumxSineNsPerOp() > 0.0);
		assertTrue(report.shapeCacheNsPerOp() > 0.0);
		assertTrue(report.chunkCacheNsPerOp() > 0.0);
		assertTrue(report.internerNsPerOp() > 0.0);
		assertTrue(report.siliciumxPerlinNsPerOp() > 0.0);
	}
}
