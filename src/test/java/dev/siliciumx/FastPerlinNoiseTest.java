package dev.siliciumx;

import dev.siliciumx.util.FastPerlinNoise;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastPerlinNoiseTest {
	@Test
	void fastPerlinMatchesVanillaPerlinNoiseBitForBit() throws Exception {
		PerlinNoise vanilla = new PerlinNoise(RandomSource.create(0x5171C10BL));

		Field permsField = GradientNoise.class.getDeclaredField("perms");
		permsField.setAccessible(true);
		byte[] perms = (byte[]) permsField.get(vanilla);

		Field offXField = GradientNoise.class.getDeclaredField("offsetX");
		Field offYField = GradientNoise.class.getDeclaredField("offsetY");
		Field offZField = GradientNoise.class.getDeclaredField("offsetZ");
		offXField.setAccessible(true);
		offYField.setAccessible(true);
		offZField.setAccessible(true);
		double offX = offXField.getDouble(vanilla);
		double offY = offYField.getDouble(vanilla);
		double offZ = offZField.getDouble(vanilla);

		Method wrapMethod = GradientNoise.class.getDeclaredMethod("wrap", double.class);
		wrapMethod.setAccessible(true);

		RandomSource rng = RandomSource.create(987654321L);
		// Test fractional coordinates, negative values, large values near wrap(), and exact grid points
		double[] specialCoords = {0.0, 1.0, -1.0, 0.5, -0.5, 16777216.0, -16777216.0, 33554432.5};
		for (double cx : specialCoords) {
			for (double cy : specialCoords) {
				double cz = cx * 0.75 - cy * 0.25;
				assertBitExactSample(vanilla, perms, offX, offY, offZ, wrapMethod, cx, cy, cz);
			}
		}

		for (int i = 0; i < 20_000; i++) {
			double x = (rng.nextDouble() - 0.5) * 4096.0;
			double y = (rng.nextDouble() - 0.5) * 512.0;
			double z = (rng.nextDouble() - 0.5) * 4096.0;
			assertBitExactSample(vanilla, perms, offX, offY, offZ, wrapMethod, x, y, z);
		}
	}

	@Test
	void fastGradDotMatchesAllSixteenVanillaGradientsExactly() throws Exception {
		Method gradDotMethod = GradientNoise.class.getDeclaredMethod("gradDot", int.class, float.class, float.class, float.class);
		gradDotMethod.setAccessible(true);

		float[] vals = {0.0f, 0.25f, 0.5f, 0.99f, -0.25f, -0.75f, -1.0f};
		for (int hash = 0; hash < 32; hash++) {
			for (float x : vals) {
				for (float y : vals) {
					for (float z : vals) {
						float expected = (float) gradDotMethod.invoke(null, hash, x, y, z);
						float actual = FastPerlinNoise.gradDot(hash, x, y, z);
						assertEquals(expected, actual, 0.0f, "Mismatch for hash=" + hash);
					}
				}
			}
		}
	}

	private static void assertBitExactSample(
		PerlinNoise vanilla,
		byte[] perms,
		double offX,
		double offY,
		double offZ,
		Method wrapMethod,
		double x,
		double y,
		double z
	) throws Exception {
		float expected = vanilla.get(x, y, z);

		double wx = ((double) wrapMethod.invoke(null, x)) + offX;
		double wy = ((double) wrapMethod.invoke(null, y)) + offY;
		double wz = ((double) wrapMethod.invoke(null, z)) + offZ;

		int ix = Mth.floor(wx);
		int iy = Mth.floor(wy);
		int iz = Mth.floor(wz);

		float fx = (float) (wx - ix);
		float fy = (float) (wy - iy);
		float fz = (float) (wz - iz);

		float actual = FastPerlinNoise.sampleAndLerp(perms, ix, iy, iz, fx, fy, fz, fy);
		assertEquals(
			Float.floatToRawIntBits(expected),
			Float.floatToRawIntBits(actual),
			"Bitwise Perlin mismatch at (" + x + ", " + y + ", " + z + "): expected=" + expected + " actual=" + actual
		);
	}
}
