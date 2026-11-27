package dev.siliciumx.client;

/**
 * Shared render-thread client state: monotonic frame counter for short-TTL culling caches,
 * cached camera coordinates for zero-overhead particle spawn/physics distance checks, and
 * font reload epoch for automatic text-width cache invalidation.
 */
public final class SiliciumXClientState {
	private static long frameCounter;
	private static volatile int fontEpoch;
	private static volatile long scoreboardEpoch;
	private static boolean cameraValid;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;

	private SiliciumXClientState() {
	}

	public static void advanceFrame() {
		frameCounter++;
	}

	public static long frameCounter() {
		return frameCounter;
	}

	public static void updateCameraPosition(double x, double y, double z) {
		cameraX = x;
		cameraY = y;
		cameraZ = z;
		cameraValid = true;
	}

	public static void clearCameraPosition() {
		cameraValid = false;
	}

	public static boolean hasCameraPosition() {
		return cameraValid;
	}

	public static double cameraX() {
		return cameraX;
	}

	public static double cameraY() {
		return cameraY;
	}

	public static double cameraZ() {
		return cameraZ;
	}

	public static int fontEpoch() {
		return fontEpoch;
	}

	public static void invalidateFontCaches() {
		fontEpoch++;
	}

	public static long scoreboardEpoch() {
		return scoreboardEpoch;
	}

	public static void bumpScoreboardEpoch() {
		scoreboardEpoch++;
	}
}
