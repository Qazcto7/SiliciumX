package dev.siliciumx.util;

/**
 * Pure heuristic helper for balancing Minecraft's {@code Util} background and IO worker thread
 * pools so background tasks do not starve the Render Thread or Server Main Thread on low-to-mid
 * core count CPUs, nor over-allocate excessive ForkJoin pools on high-core CPUs.
 */
public final class ThreadPoolTuner {
	private static final int MAX_AUTO_BACKGROUND_THREADS = 12;

	private ThreadPoolTuner() {
	}

	/**
	 * Computes a balanced worker thread count for {@code Util.backgroundExecutor()}.
	 * Always respects an explicit {@code -Dmax.bg.threads} JVM property when present.
	 */
	public static int computeBackgroundThreadCount(
		int availableProcessors,
		int vanillaCount,
		boolean hasExplicitMaxBgThreadsProp
	) {
		int safeVanilla = Math.max(1, vanillaCount);
		if (hasExplicitMaxBgThreadsProp) {
			return safeVanilla;
		}
		if (availableProcessors <= 2) {
			return 1;
		}
		if (availableProcessors <= 4) {
			return Math.min(safeVanilla, 2);
		}
		int headroomTarget = Math.max(2, availableProcessors - 2);
		return Math.min(safeVanilla, Math.min(headroomTarget, MAX_AUTO_BACKGROUND_THREADS));
	}

	/**
	 * Returns a thread priority strictly below {@link Thread#NORM_PRIORITY} so OS schedulers
	 * prioritize the Render Thread, Audio Thread, and Server Main Thread over background workers.
	 */
	public static int backgroundWorkerPriority(int availableProcessors) {
		int priority = availableProcessors <= 4 ? Thread.NORM_PRIORITY - 2 : Thread.NORM_PRIORITY - 1;
		return Math.max(Thread.MIN_PRIORITY, Math.min(Thread.NORM_PRIORITY - 1, priority));
	}

	/**
	 * Returns a low priority for disk/download IO worker threads so IO bursts do not cause frame hitches.
	 */
	public static int ioWorkerPriority() {
		return Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);
	}
}
