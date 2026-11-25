package dev.siliciumx;

import dev.siliciumx.util.CompactSineLut;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSineLutTest {
	@Test
	void matchesAll65536VanillaLutSlotsWithinOneUlp() {
		float maxError = 0.0f;
		for (int i = 0; i < 65536; i++) {
			final int idx = i;
			float vanilla = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
			float compact = CompactSineLut.lookup(i);
			float err = Math.abs(vanilla - compact);
			if (err > maxError) {
				maxError = err;
			}
			assertTrue(
				err <= 1.2e-7f,
				() -> "Mismatch at index " + idx + ": vanilla=" + vanilla + ", compact=" + compact + ", err=" + err
			);
		}
		assertTrue(maxError <= 1.2e-7f, "Max error too high: " + maxError);
	}

	@Test
	void cardinalAnglesAreExact() {
		assertEquals(0.0f, CompactSineLut.lookup(0));
		assertEquals(1.0f, CompactSineLut.lookup(16384));
		assertEquals(0.0f, CompactSineLut.lookup(32768));
		assertEquals(-1.0f, CompactSineLut.lookup(49152));

		assertEquals(0.0f, CompactSineLut.sin(0.0));
		assertEquals(1.0f, CompactSineLut.cos(0.0));
	}

	@Test
	void sinAndCosMatchVanillaQuantizationAcrossNegativeAndLargeAngles() {
		Random rng = new Random(0x511C10L);
		double scale = 65536.0 / (Math.PI * 2.0);
		for (int i = 0; i < 50_000; i++) {
			double angle = (rng.nextDouble() - 0.5) * 100_000.0;
			int sinIdx = (int) ((long) (angle * scale) & 65535L);
			int cosIdx = (int) ((long) (angle * scale + 16384.0) & 65535L);

			float expectedSin = (float) Math.sin((double) sinIdx * Math.PI * 2.0 / 65536.0);
			float expectedCos = (float) Math.sin((double) cosIdx * Math.PI * 2.0 / 65536.0);

			assertEquals(expectedSin, CompactSineLut.sin(angle), 1.2e-7f);
			assertEquals(expectedCos, CompactSineLut.cos(angle), 1.2e-7f);
		}
	}
}
