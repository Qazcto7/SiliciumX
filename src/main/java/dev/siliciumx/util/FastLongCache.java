package dev.siliciumx.util;

import java.util.Arrays;

/**
 * Fixed-capacity, zero-allocation 2-way associative cache from {@code long} keys to {@code V} values.
 * Designed for hot-path single-threaded Minecraft subsystems (such as {@code ServerChunkCache})
 * where HashMap allocation or rehashing spikes would introduce GC jitter.
 */
public final class FastLongCache<V> {
	private final int mask;
	private final long[] keys;
	private final Object[] values;
	private final boolean[] occupied;

	public FastLongCache(int requestedCapacity) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(16, requestedCapacity));
		this.mask = capacity - 1;
		this.keys = new long[capacity];
		this.values = new Object[capacity];
		this.occupied = new boolean[capacity];
	}

	/**
	 * Mixes 64-bit packed coordinates (such as ChunkPos.pack()) using SplitMix64 finalizer
	 * for strong avalanche across neighboring coordinates.
	 */
	private static int mix(long key) {
		long z = key;
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return (int) (z ^ (z >>> 31));
	}

	@SuppressWarnings("unchecked")
	public V get(long key) {
		int slot = mix(key) & this.mask;
		if (this.occupied[slot] && this.keys[slot] == key) {
			return (V) this.values[slot];
		}
		int secondSlot = (slot + 1) & this.mask;
		if (this.occupied[secondSlot] && this.keys[secondSlot] == key) {
			return (V) this.values[secondSlot];
		}
		return null;
	}

	public void put(long key, V value) {
		int slot = mix(key) & this.mask;
		if (!this.occupied[slot] || this.keys[slot] == key) {
			this.keys[slot] = key;
			this.values[slot] = value;
			this.occupied[slot] = true;
			return;
		}
		int secondSlot = (slot + 1) & this.mask;
		if (!this.occupied[secondSlot] || this.keys[secondSlot] == key) {
			this.keys[secondSlot] = key;
			this.values[secondSlot] = value;
			this.occupied[secondSlot] = true;
			return;
		}
		// 2-way associative eviction: shift primary to secondary and place newest in primary.
		this.keys[secondSlot] = this.keys[slot];
		this.values[secondSlot] = this.values[slot];
		this.keys[slot] = key;
		this.values[slot] = value;
	}

	public void clear() {
		Arrays.fill(this.occupied, false);
		Arrays.fill(this.values, null);
	}

	public int capacity() {
		return this.keys.length;
	}
}
