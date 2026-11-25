package dev.siliciumx.util;

import java.util.Arrays;

/**
 * Thread-safe, fixed-size, zero-allocation rolling window tracker for nanosecond timings
 * (server MSPT and client frame times).
 */
public final class RollingTimingWindow {
	private final long[] samplesNanos;
	private final int capacity;
	private int cursor;
	private int count;

	public RollingTimingWindow(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be > 0");
		}
		this.capacity = capacity;
		this.samplesNanos = new long[capacity];
	}

	public synchronized void recordNanos(long nanos) {
		if (nanos < 0L) {
			return;
		}
		int idx = this.cursor;
		this.samplesNanos[idx] = nanos;
		idx++;
		if (idx == this.capacity) {
			idx = 0;
		}
		this.cursor = idx;
		if (this.count < this.capacity) {
			this.count++;
		}
	}

	public synchronized void reset() {
		this.cursor = 0;
		this.count = 0;
	}

	public Snapshot snapshot() {
		long[] copy;
		int n;
		synchronized (this) {
			n = this.count;
			if (n == 0) {
				return Snapshot.EMPTY;
			}
			copy = new long[n];
			System.arraycopy(this.samplesNanos, 0, copy, 0, n);
		}
		Arrays.sort(copy);

		long sum = 0L;
		for (int i = 0; i < n; i++) {
			sum += copy[i];
		}

		double meanMs = (sum / (double) n) / 1_000_000.0;
		double p50Ms = copy[percentileIndex(n, 0.50)] / 1_000_000.0;
		double p95Ms = copy[percentileIndex(n, 0.95)] / 1_000_000.0;
		double p99Ms = copy[percentileIndex(n, 0.99)] / 1_000_000.0;
		double minMs = copy[0] / 1_000_000.0;
		double maxMs = copy[n - 1] / 1_000_000.0;

		return new Snapshot(n, meanMs, minMs, p50Ms, p95Ms, p99Ms, maxMs);
	}

	private static int percentileIndex(int n, double p) {
		int idx = (int) Math.ceil(p * n) - 1;
		return Math.max(0, Math.min(n - 1, idx));
	}

	public record Snapshot(
		int samples,
		double meanMs,
		double minMs,
		double p50Ms,
		double p95Ms,
		double p99Ms,
		double maxMs
	) {
		public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0);

		public double estimatedTps() {
			if (samples == 0) {
				return 0.0;
			}
			if (meanMs <= 50.0) {
				return 20.0;
			}
			return 1000.0 / meanMs;
		}

		public double estimatedFps() {
			if (samples == 0 || meanMs <= 0.0) {
				return 0.0;
			}
			return 1000.0 / meanMs;
		}
	}
}
