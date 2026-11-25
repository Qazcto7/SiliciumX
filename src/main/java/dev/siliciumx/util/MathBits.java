package dev.siliciumx.util;

/**
 * Shared bit-manipulation helpers for power-of-two fixed-capacity caches.
 */
public final class MathBits {
	private MathBits() {
	}

	public static int nextPowerOfTwo(int value) {
		int n = value - 1;
		n |= n >>> 1;
		n |= n >>> 2;
		n |= n >>> 4;
		n |= n >>> 8;
		n |= n >>> 16;
		return (n < 0) ? 1 : (n >= (1 << 30) ? (1 << 30) : n + 1);
	}
}
