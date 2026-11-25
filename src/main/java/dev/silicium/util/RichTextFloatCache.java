package dev.silicium.util;

import java.util.Arrays;

/**
 * Zero-allocation, bounded, direct-mapped cache mapping immutable
 * {@code (textOrKey, style, translatable)} tuples to computed float widths.
 *
 * <p>Designed specifically for single-thread render-loop lookups in
 * {@code StringSplitter.stringWidth(FormattedText)} where {@code MutableComponent} objects cannot
 * safely be used directly as cache keys because callers may mutate them after creation.</p>
 */
public final class RichTextFloatCache {
	private final String[] textKeys;
	private final Object[] styleKeys;
	private final boolean[] translatableFlags;
	private final float[] values;
	private final int mask;
	private final int maxLength;

	public RichTextFloatCache(int requestedCapacity, int maxLength) {
		int capacity = MathBits.nextPowerOfTwo(Math.max(16, requestedCapacity));
		this.textKeys = new String[capacity];
		this.styleKeys = new Object[capacity];
		this.translatableFlags = new boolean[capacity];
		this.values = new float[capacity];
		this.mask = capacity - 1;
		this.maxLength = maxLength;
	}

	public boolean isEligible(String text) {
		return text != null && !text.isEmpty() && text.length() <= this.maxLength;
	}

	/**
	 * Returns cached width or {@link Float#NaN} on cache miss.
	 */
	public float get(String text, Object style, boolean translatable) {
		int slot = hashSlot(text, style, translatable);
		String cachedText = this.textKeys[slot];
		if (cachedText != null
			&& this.translatableFlags[slot] == translatable
			&& (cachedText == text || cachedText.equals(text))
		) {
			Object cachedStyle = this.styleKeys[slot];
			if (cachedStyle == style || (cachedStyle != null && cachedStyle.equals(style))) {
				return this.values[slot];
			}
		}
		return Float.NaN;
	}

	public void put(String text, Object style, boolean translatable, float value) {
		int slot = hashSlot(text, style, translatable);
		this.textKeys[slot] = text;
		this.styleKeys[slot] = style;
		this.translatableFlags[slot] = translatable;
		this.values[slot] = value;
	}

	public void clear() {
		Arrays.fill(this.textKeys, null);
		Arrays.fill(this.styleKeys, null);
	}

	private int hashSlot(String text, Object style, boolean translatable) {
		int h = text.hashCode() * 31 + (style != null ? style.hashCode() : 0);
		if (translatable) {
			h ^= 0x9E3779B9;
		}
		h ^= (h >>> 16);
		return h & this.mask;
	}
}
