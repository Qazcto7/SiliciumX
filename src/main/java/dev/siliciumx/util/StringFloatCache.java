package dev.siliciumx.util;

import java.util.Arrays;

/**
 * Fixed-capacity, zero-boxing 2-way associative cache mapping short {@link String} keys to primitive
 * {@code float} values (using {@link Float#NaN} as the miss sentinel).
 */
public final class StringFloatCache {
	private final int mask;
	private final int maxKeyLength;
	private final String[] keys;
	private final float[] values;

	public StringFloatCache(int requestedCapacity, int maxKeyLength) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(32, requestedCapacity));
		this.mask = capacity - 1;
		this.maxKeyLength = maxKeyLength;
		this.keys = new String[capacity];
		this.values = new float[capacity];
	}

	private static int mixHash(int h) {
		return h ^ (h >>> 16);
	}

	public boolean isEligible(String key) {
		return key != null && !key.isEmpty() && key.length() <= this.maxKeyLength;
	}

	/**
	 * Returns the cached width or {@link Float#NaN} on cache miss.
	 */
	public float get(String key) {
		int slot = mixHash(key.hashCode()) & this.mask;
		String k0 = this.keys[slot];
		if (k0 != null && (k0 == key || k0.equals(key))) {
			return this.values[slot];
		}
		int secondSlot = (slot + 1) & this.mask;
		String k1 = this.keys[secondSlot];
		if (k1 != null && (k1 == key || k1.equals(key))) {
			return this.values[secondSlot];
		}
		return Float.NaN;
	}

	public void put(String key, float value) {
		if (!isEligible(key) || Float.isNaN(value)) {
			return;
		}
		int slot = mixHash(key.hashCode()) & this.mask;
		String k0 = this.keys[slot];
		if (k0 == null || k0 == key || k0.equals(key)) {
			this.keys[slot] = key;
			this.values[slot] = value;
			return;
		}
		int secondSlot = (slot + 1) & this.mask;
		String k1 = this.keys[secondSlot];
		if (k1 == null || k1 == key || k1.equals(key)) {
			this.keys[secondSlot] = key;
			this.values[secondSlot] = value;
			return;
		}
		this.keys[secondSlot] = k0;
		this.values[secondSlot] = this.values[slot];
		this.keys[slot] = key;
		this.values[slot] = value;
	}

	public void clear() {
		Arrays.fill(this.keys, null);
	}

	public int capacity() {
		return this.keys.length;
	}
}
