package dev.siliciumx.client.cull;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Multi-core, zero-allocation asynchronous occlusion engine that offloads 9-point 3D DDA solid-block
 * raycasts off the Minecraft Render Thread onto dedicated daemon worker threads.
 *
 * <p>Architecture &amp; safety guarantees:</p>
 * <ul>
 *   <li><b>Wait-free SPSC ring buffer</b> between the single Render Thread producer and dedicated
 *       worker consumer — zero locks, zero heap allocations per frame, never stalls rendering.</li>
 *   <li><b>Tear-free SeqLock result slots</b> guarantee atomic consistency across camera position,
 *       frame stamp, epoch, and occlusion bit without blocking reads.</li>
 *   <li><b>Zero pop-in guarantee:</b> First-seen targets and large camera movements return visible
 *       ({@code false}) on frame 0 while queueing background evaluation; proactive background refreshes
 *       begin at 0.15 blocks camera delta before hard invalidation at 0.30 blocks.</li>
 * </ul>
 */
public final class AsyncOcclusionEngine {
	private static final int RESULT_CAPACITY = 2048;
	private static final int RESULT_MASK = RESULT_CAPACITY - 1;
	private static final int QUEUE_CAPACITY = 2048;
	private static final int QUEUE_MASK = QUEUE_CAPACITY - 1;

	private static final double PROACTIVE_REFRESH_DIST_SQ = 0.0225; // 0.15^2 blocks
	private static final double HARD_INVALIDATE_DIST_SQ = 0.09; // 0.30^2 blocks
	private static final long WORKER_IDLE_PARK_NS = 100_000_000L; // 100 ms idle safety wakeup

	private static final List<AsyncOcclusionEngine> INSTANCES = new CopyOnWriteArrayList<>();

	private final ResultSlot[] results = new ResultSlot[RESULT_CAPACITY];
	// Render-thread-only per-slot enqueue deduplication within a single frame
	private final long[] lastEnqueuedFrame = new long[RESULT_CAPACITY];

	// Wait-free SPSC ring buffer (Render Thread produces at head; Worker consumes at tail)
	private final long[] qKey = new long[QUEUE_CAPACITY];
	private final double[] qMinX = new double[QUEUE_CAPACITY];
	private final double[] qMinY = new double[QUEUE_CAPACITY];
	private final double[] qMinZ = new double[QUEUE_CAPACITY];
	private final double[] qMaxX = new double[QUEUE_CAPACITY];
	private final double[] qMaxY = new double[QUEUE_CAPACITY];
	private final double[] qMaxZ = new double[QUEUE_CAPACITY];
	private final double[] qCamX = new double[QUEUE_CAPACITY];
	private final double[] qCamY = new double[QUEUE_CAPACITY];
	private final double[] qCamZ = new double[QUEUE_CAPACITY];
	private final long[] qFrame = new long[QUEUE_CAPACITY];
	private final int[] qEpoch = new int[QUEUE_CAPACITY];
	private final int[] qMaxSteps = new int[QUEUE_CAPACITY];

	private volatile long head;
	private volatile long tail;
	private volatile BlockGetter activeLevel;
	private volatile int epoch = 1;
	private volatile boolean running = true;
	private final AtomicBoolean parked = new AtomicBoolean(false);
	private final Thread workerThread;

	public AsyncOcclusionEngine(String roleName) {
		for (int i = 0; i < RESULT_CAPACITY; i++) {
			this.results[i] = new ResultSlot();
			this.lastEnqueuedFrame[i] = Long.MIN_VALUE;
		}
		this.workerThread = new Thread(this::workerLoop, "SiliciumX-AsyncCull-" + roleName);
		this.workerThread.setDaemon(true);
		this.workerThread.setPriority(Thread.NORM_PRIORITY);
		this.workerThread.start();
		INSTANCES.add(this);
	}

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
	 * Queries the latest background occlusion verdict for an AABB and schedules off-thread raycasting
	 * whenever needed. Always returns in O(1) without running raycasts on the Render Thread.
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
		if (!this.running || level == null) {
			return false;
		}
		if (level != this.activeLevel) {
			this.activeLevel = level;
			this.epoch++;
		}

		long frame = SiliciumXClientState.frameCounter();
		SiliciumXConfig cfg = SiliciumXConfig.get();
		int ttl = cfg.occlusionCacheFrames;
		int curEpoch = this.epoch;
		int slotIdx = OcclusionRaycaster.mixKey(cacheKey) & RESULT_MASK;
		ResultSlot slot = this.results[slotIdx];

		int s1 = slot.seq;
		if ((s1 & 1) == 0 && s1 != 0) {
			long key = slot.key;
			long evalFrame = slot.evalFrame;
			double ex = slot.evalCamX;
			double ey = slot.evalCamY;
			double ez = slot.evalCamZ;
			boolean occluded = slot.occluded;
			int slotEpoch = slot.evalEpoch;
			VarHandle.loadLoadFence();

			if (slot.seq == s1 && slotEpoch == curEpoch && key == cacheKey) {
				long age = frame - evalFrame;
				double dx = camX - ex;
				double dy = camY - ey;
				double dz = camZ - ez;
				double distSq = dx * dx + dy * dy + dz * dz;

				if (age >= 0L && age < ttl && distSq <= HARD_INVALIDATE_DIST_SQ) {
					// Proactive background refresh when approaching TTL or drifting slightly
					if (age >= Math.max(1L, ttl - 1L) || distSq > PROACTIVE_REFRESH_DIST_SQ) {
						enqueueIfNeeded(
							slotIdx, cacheKey, curEpoch, frame, cfg.occlusionMaxRaySteps,
							camX, camY, camZ, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ
						);
					}
					return occluded;
				}
			}
		}

		enqueueIfNeeded(
			slotIdx, cacheKey, curEpoch, frame, cfg.occlusionMaxRaySteps,
			camX, camY, camZ, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ
		);
		return false;
	}

	/**
	 * Invalidates cached occlusion results and clears the active level reference to prevent leaks.
	 */
	public void clear() {
		this.activeLevel = null;
		this.epoch++;
	}

	/**
	 * Clears cached results when switching to a specific new level.
	 */
	public void clear(BlockGetter newLevel) {
		this.activeLevel = newLevel;
		this.epoch++;
	}

	public void shutdown() {
		this.running = false;
		this.activeLevel = null;
		this.epoch++;
		LockSupport.unpark(this.workerThread);
		INSTANCES.remove(this);
	}

	public static void clearAll() {
		for (AsyncOcclusionEngine engine : INSTANCES) {
			engine.clear();
		}
	}

	public static void shutdownAll() {
		for (AsyncOcclusionEngine engine : INSTANCES) {
			engine.shutdown();
		}
	}

	private void enqueueIfNeeded(
		int slotIdx,
		long key,
		int curEpoch,
		long frame,
		int maxSteps,
		double camX, double camY, double camZ,
		double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ
	) {
		if (this.lastEnqueuedFrame[slotIdx] == frame) {
			return;
		}
		long h = this.head;
		long t = this.tail;
		if ((h - t) >= QUEUE_CAPACITY) {
			return;
		}

		this.lastEnqueuedFrame[slotIdx] = frame;
		int idx = (int) (h & QUEUE_MASK);
		this.qKey[idx] = key;
		this.qMinX[idx] = minX;
		this.qMinY[idx] = minY;
		this.qMinZ[idx] = minZ;
		this.qMaxX[idx] = maxX;
		this.qMaxY[idx] = maxY;
		this.qMaxZ[idx] = maxZ;
		this.qCamX[idx] = camX;
		this.qCamY[idx] = camY;
		this.qCamZ[idx] = camZ;
		this.qFrame[idx] = frame;
		this.qEpoch[idx] = curEpoch;
		this.qMaxSteps[idx] = maxSteps;

		// Release fence via volatile write guarantees worker observes all slot fields
		this.head = h + 1L;
		if (this.parked.compareAndSet(true, false)) {
			LockSupport.unpark(this.workerThread);
		}
	}

	private void workerLoop() {
		BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
		while (this.running) {
			long t = this.tail;
			long h = this.head;
			if (t >= h) {
				this.parked.set(true);
				if (this.tail < this.head || !this.running) {
					this.parked.set(false);
					continue;
				}
				LockSupport.parkNanos(WORKER_IDLE_PARK_NS);
				this.parked.set(false);
				continue;
			}

			int idx = (int) (t & QUEUE_MASK);
			long key = this.qKey[idx];
			double minX = this.qMinX[idx];
			double minY = this.qMinY[idx];
			double minZ = this.qMinZ[idx];
			double maxX = this.qMaxX[idx];
			double maxY = this.qMaxY[idx];
			double maxZ = this.qMaxZ[idx];
			double camX = this.qCamX[idx];
			double camY = this.qCamY[idx];
			double camZ = this.qCamZ[idx];
			long frame = this.qFrame[idx];
			int jobEpoch = this.qEpoch[idx];
			int maxSteps = this.qMaxSteps[idx];

			// Release queue slot before performing raycasts
			this.tail = t + 1L;

			BlockGetter level = this.activeLevel;
			if (level == null || jobEpoch != this.epoch) {
				continue;
			}

			boolean occluded = OcclusionRaycaster.isBoxOccludedStateless(
				level,
				scratchPos,
				maxSteps,
				camX, camY, camZ,
				minX, minY, minZ,
				maxX, maxY, maxZ
			);
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ASYNC_OCCLUSION_JOBS);

			if (jobEpoch == this.epoch && this.running) {
				int rSlot = OcclusionRaycaster.mixKey(key) & RESULT_MASK;
				ResultSlot rs = this.results[rSlot];
				int s = rs.seq;
				rs.seq = s + 1;
				VarHandle.storeStoreFence();
				rs.key = key;
				rs.evalFrame = frame;
				rs.evalCamX = camX;
				rs.evalCamY = camY;
				rs.evalCamZ = camZ;
				rs.occluded = occluded;
				rs.evalEpoch = jobEpoch;
				rs.seq = s + 2;
			}
		}
	}

	private static final class ResultSlot {
		volatile int seq;
		long key;
		long evalFrame;
		double evalCamX;
		double evalCamY;
		double evalCamZ;
		boolean occluded;
		int evalEpoch;
	}
}
