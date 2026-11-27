package dev.siliciumx;

import dev.siliciumx.util.ThreadPoolTuner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolTunerTest {
	@Test
	void balancesBackgroundThreadsAcrossCoreCountsAndRespectsExplicitFlag() {
		assertEquals(1, ThreadPoolTuner.computeBackgroundThreadCount(1, 1, false));
		assertEquals(1, ThreadPoolTuner.computeBackgroundThreadCount(2, 1, false));
		assertEquals(2, ThreadPoolTuner.computeBackgroundThreadCount(4, 3, false));
		assertEquals(4, ThreadPoolTuner.computeBackgroundThreadCount(6, 5, false));
		assertEquals(6, ThreadPoolTuner.computeBackgroundThreadCount(8, 7, false));
		assertEquals(12, ThreadPoolTuner.computeBackgroundThreadCount(16, 15, false));
		assertEquals(12, ThreadPoolTuner.computeBackgroundThreadCount(32, 31, false));

		// Explicit -Dmax.bg.threads must never be overridden
		assertEquals(20, ThreadPoolTuner.computeBackgroundThreadCount(4, 20, true));
		assertEquals(1, ThreadPoolTuner.computeBackgroundThreadCount(16, 0, true));
	}

	@Test
	void keepsWorkerPrioritiesStrictlyBelowNormPriority() {
		for (int cores : new int[]{1, 2, 4, 6, 8, 16, 32}) {
			int bgPrio = ThreadPoolTuner.backgroundWorkerPriority(cores);
			assertTrue(bgPrio >= Thread.MIN_PRIORITY && bgPrio < Thread.NORM_PRIORITY);
		}
		int ioPrio = ThreadPoolTuner.ioWorkerPriority();
		assertTrue(ioPrio >= Thread.MIN_PRIORITY && ioPrio < Thread.NORM_PRIORITY);
		assertTrue(ThreadPoolTuner.backgroundWorkerPriority(4) <= ThreadPoolTuner.backgroundWorkerPriority(8));
	}
}
