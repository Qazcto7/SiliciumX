package dev.siliciumx.util;

/**
 * Lock-free, fixed-capacity, zero-leak bounded canonicalizer for immutable {@link String} keys
 * (primarily NBT CompoundTag field names).
 *
 * <p>Unlike {@link String#intern()} (which grows the JVM native string table and risks lock contention),
 * this pool uses a fixed-size power-of-two array with 2-way associative replacement: worst-case memory
 * is strictly bounded, lookups are O(1) with zero locks, and safe publication relies on {@link String}'s
 * immutable final fields (JLS §17.5).</p>
 */
public final class BoundedStringInterner {
	private final String[] table;
	private final int mask;
	private final int maxLength;

	public BoundedStringInterner(int requestedCapacity, int maxLength) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(64, requestedCapacity));
		this.table = new String[capacity];
		this.mask = capacity - 1;
		this.maxLength = maxLength;
	}

	/**
	 * Returns a canonical equal {@link String} instance when present in the pool,
	 * or installs and returns {@code input} otherwise.
	 */
	public String intern(String input) {
		if (input == null || input.isEmpty() || input.length() > this.maxLength) {
			return input;
		}
		int hash = input.hashCode();
		int slot = (hash ^ (hash >>> 16)) & this.mask;
		String existing = this.table[slot];
		if (existing != null && (existing == input || existing.equals(input))) {
			return existing;
		}
		int secondSlot = (slot + 1) & this.mask;
		String second = this.table[secondSlot];
		if (second != null && (second == input || second.equals(input))) {
			return second;
		}
		if (existing != null) {
			this.table[secondSlot] = existing;
		}
		this.table[slot] = input;
		return input;
	}

	public int capacity() {
		return this.table.length;
	}
}
