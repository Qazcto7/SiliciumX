package dev.siliciumx.util;

import java.util.Arrays;

/**
 * Allocation-free per-tick burst counter for bounding identical event spam (such as dozens of
 * simultaneous XP pickups, chickens, or pistons in {@code SoundEngine.play}).
 *
 * <p>Uses a small fixed-size linear-probed table that resets automatically whenever the tick index
 * advances, incurring zero heap allocations during normal gameplay.</p>
 */
public final class TickBurstCounter<K> {
	private final Object[] keys;
	private final int[] counts;
	private final int mask;
	private int lastTick = Integer.MIN_VALUE;
	private int occupied;

	public TickBurstCounter(int requestedCapacity) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(16, requestedCapacity));
		this.keys = new Object[capacity];
		this.counts = new int[capacity];
		this.mask = capacity - 1;
	}

	/**
	 * Attempts to acquire one permit for {@code key} during {@code currentTick}.
	 *
	 * @param currentTick current monotonic tick counter
	 * @param key         non-null key identifying the event (e.g. sound {@code Identifier})
	 * @param maxPerTick  maximum allowed occurrences per tick ({@code <= 0} always allows)
	 * @return {@code true} if allowed; {@code false} if the per-tick cap for this key was reached
	 */
	public boolean tryAcquire(int currentTick, K key, int maxPerTick) {
		if (maxPerTick <= 0 || key == null) {
			return true;
		}
		if (currentTick != this.lastTick) {
			resetForTick(currentTick);
		}

		int idx = mix(key.hashCode()) & this.mask;
		for (int probes = 0; probes < 8; probes++) {
			Object existing = this.keys[idx];
			if (existing == null) {
				this.keys[idx] = key;
				this.counts[idx] = 1;
				this.occupied++;
				return true;
			}
			if (existing == key || existing.equals(key)) {
				int count = this.counts[idx];
				if (count >= maxPerTick) {
					return false;
				}
				this.counts[idx] = count + 1;
				return true;
			}
			idx = (idx + 1) & this.mask;
		}

		// Table probe window saturated by distinct sounds in one tick -> conservatively allow.
		return true;
	}

	public void clear() {
		if (this.occupied > 0) {
			Arrays.fill(this.keys, null);
			Arrays.fill(this.counts, 0);
			this.occupied = 0;
		}
		this.lastTick = Integer.MIN_VALUE;
	}

	private void resetForTick(int currentTick) {
		if (this.occupied > 0) {
			Arrays.fill(this.keys, null);
			Arrays.fill(this.counts, 0);
			this.occupied = 0;
		}
		this.lastTick = currentTick;
	}

	private static int mix(int h) {
		h ^= (h >>> 16);
		h *= 0x7feb352d;
		h ^= (h >>> 15);
		return h;
	}
}
