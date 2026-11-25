package dev.siliciumx.util;

/**
 * Bit-identical, allocation-free gradient and 3D Perlin noise evaluation matching Minecraft 26.3's
 * {@code GradientNoise} and {@code PerlinNoise.sampleAndLerp}.
 *
 * <p>Eliminates per-call {@code GradientNoise.GRADIENT[]} heap record dereferences, {@code i2f}
 * conversions, virtual {@code permute()} method calls, and static {@code Mth.smoothstep} /
 * {@code Mth.lerp3} dispatch hops while preserving exact IEEE-754 floating-point evaluation order
 * so generated worlds are bit-for-bit identical to Vanilla.</p>
 */
public final class FastPerlinNoise {
	private FastPerlinNoise() {
	}

	/**
	 * Branchless switch computing {@code GradientNoise.GRADIENT[hash & 15].dot(x, y, z)} without
	 * dereferencing heap {@code Gradient} record objects or converting {@code int} gradient vectors
	 * to {@code float}.
	 */
	public static float gradDot(int hash, float x, float y, float z) {
		return switch (hash & 15) {
			case 0, 12 -> x + y;
			case 1, 14 -> -x + y;
			case 2 -> x - y;
			case 3 -> -x - y;
			case 4 -> x + z;
			case 5 -> -x + z;
			case 6 -> x - z;
			case 7 -> -x - z;
			case 8 -> y + z;
			case 9, 13 -> -y + z;
			case 10 -> y - z;
			case 11, 15 -> -y - z;
			default -> 0.0f;
		};
	}

	/**
	 * Bit-identical replacement for {@code PerlinNoise.sampleAndLerp(int, int, int, float, float, float, float)}.
	 */
	public static float sampleAndLerp(
		byte[] perms,
		int x,
		int y,
		int z,
		float fx,
		float fy,
		float fz,
		float yFade
	) {
		int px0 = perms[x & 255] & 255;
		int px1 = perms[(x + 1) & 255] & 255;

		int pxy00 = perms[(px0 + y) & 255] & 255;
		int pxy01 = perms[(px0 + y + 1) & 255] & 255;
		int pxy10 = perms[(px1 + y) & 255] & 255;
		int pxy11 = perms[(px1 + y + 1) & 255] & 255;

		float fxm1 = fx - 1.0f;
		float fym1 = fy - 1.0f;
		float fzm1 = fz - 1.0f;

		float c000 = gradDot(perms[(pxy00 + z) & 255] & 255, fx, fy, fz);
		float c100 = gradDot(perms[(pxy10 + z) & 255] & 255, fxm1, fy, fz);
		float c010 = gradDot(perms[(pxy01 + z) & 255] & 255, fx, fym1, fz);
		float c110 = gradDot(perms[(pxy11 + z) & 255] & 255, fxm1, fym1, fz);
		float c001 = gradDot(perms[(pxy00 + z + 1) & 255] & 255, fx, fy, fzm1);
		float c101 = gradDot(perms[(pxy10 + z + 1) & 255] & 255, fxm1, fy, fzm1);
		float c011 = gradDot(perms[(pxy01 + z + 1) & 255] & 255, fx, fym1, fzm1);
		float c111 = gradDot(perms[(pxy11 + z + 1) & 255] & 255, fxm1, fym1, fzm1);

		float sx = fx * fx * fx * (fx * (fx * 6.0f - 15.0f) + 10.0f);
		float sy = yFade * yFade * yFade * (yFade * (yFade * 6.0f - 15.0f) + 10.0f);
		float sz = fz * fz * fz * (fz * (fz * 6.0f - 15.0f) + 10.0f);

		// Preserve exact Vanilla Mth.lerp3 evaluation order
		float x00 = c000 + sx * (c100 - c000);
		float x10 = c010 + sx * (c110 - c010);
		float z0 = x00 + sy * (x10 - x00);

		float x01 = c001 + sx * (c101 - c001);
		float x11 = c011 + sx * (c111 - c011);
		float z1 = x01 + sy * (x11 - x01);

		return z0 + sz * (z1 - z0);
	}
}
