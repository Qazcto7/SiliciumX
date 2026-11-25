package dev.silicium.memory;

import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.nbt.StringTag;

/**
 * Lock-free, fixed-capacity, zero-leak canonical pool for short immutable {@link StringTag} instances.
 *
 * <p>Eliminates tens of thousands of duplicate {@code StringTag} record objects and duplicate backing
 * {@code String} values created during chunk palette, entity, item, and packet NBT deserialization.</p>
 */
public final class StringTagPool {
	private static final int CAPACITY = 4096;
	private static final int MASK = CAPACITY - 1;
	private static final int MAX_STRING_LENGTH = 64;

	private static final StringTag[] TABLE = new StringTag[CAPACITY];

	private StringTagPool() {
	}

	/**
	 * Returns a canonical {@link StringTag} with value equal to {@code value}, or {@code null}
	 * if the string is ineligible (empty or longer than {@value #MAX_STRING_LENGTH} chars).
	 */
	@SuppressWarnings("removal")
	public static StringTag internOrCreate(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_STRING_LENGTH) {
			return null;
		}
		int hash = value.hashCode();
		int slot = (hash ^ (hash >>> 16)) & MASK;

		StringTag existing = TABLE[slot];
		if (existing != null && (existing.value() == value || existing.value().equals(value))) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.NBT_STRINGS_DEDUPED);
			return existing;
		}

		int secondSlot = (slot + 1) & MASK;
		StringTag second = TABLE[secondSlot];
		if (second != null && (second.value() == value || second.value().equals(value))) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.NBT_STRINGS_DEDUPED);
			return second;
		}

		StringTag created = new StringTag(value);
		if (existing != null) {
			TABLE[secondSlot] = existing;
		}
		TABLE[slot] = created;
		return created;
	}
}
