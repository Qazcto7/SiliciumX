package dev.siliciumx.util;

/**
 * Zero-allocation, fixed-capacity, 2-way associative identity-keyed cache mapping a pair of object
 * references {@code (first, second)} to a cached {@code byte} outcome ({@code 0 = false}, {@code 1 = true},
 * {@code -1 = miss}).
 */
public final class ShapePairCache {
	public static final byte MISS = -1;
	public static final byte FALSE = 0;
	public static final byte TRUE = 1;

	private final int mask;
	private final Object[] firstKeys;
	private final Object[] secondKeys;
	private final byte[] results;

	public ShapePairCache(int requestedCapacity) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(32, requestedCapacity));
		this.mask = capacity - 1;
		this.firstKeys = new Object[capacity];
		this.secondKeys = new Object[capacity];
		this.results = new byte[capacity];
	}

	private static int mix(Object first, Object second) {
		int h = System.identityHashCode(first) * 31 + System.identityHashCode(second);
		h ^= (h >>> 16);
		h *= 0x7feb352d;
		h ^= (h >>> 15);
		return h;
	}

	public byte get(Object first, Object second) {
		int slot = mix(first, second) & this.mask;
		if (this.firstKeys[slot] == first && this.secondKeys[slot] == second) {
			return this.results[slot];
		}
		int secondSlot = (slot + 1) & this.mask;
		if (this.firstKeys[secondSlot] == first && this.secondKeys[secondSlot] == second) {
			return this.results[secondSlot];
		}
		return MISS;
	}

	public void put(Object first, Object second, boolean value) {
		byte encoded = value ? TRUE : FALSE;
		int slot = mix(first, second) & this.mask;
		if (this.firstKeys[slot] == null || (this.firstKeys[slot] == first && this.secondKeys[slot] == second)) {
			this.firstKeys[slot] = first;
			this.secondKeys[slot] = second;
			this.results[slot] = encoded;
			return;
		}
		int secondSlot = (slot + 1) & this.mask;
		if (this.firstKeys[secondSlot] == null || (this.firstKeys[secondSlot] == first && this.secondKeys[secondSlot] == second)) {
			this.firstKeys[secondSlot] = first;
			this.secondKeys[secondSlot] = second;
			this.results[secondSlot] = encoded;
			return;
		}
		this.firstKeys[secondSlot] = this.firstKeys[slot];
		this.secondKeys[secondSlot] = this.secondKeys[slot];
		this.results[secondSlot] = this.results[slot];

		this.firstKeys[slot] = first;
		this.secondKeys[slot] = second;
		this.results[slot] = encoded;
	}

	public int capacity() {
		return this.results.length;
	}
}
