package dev.silicium.client.mixin.render;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.WeatherColumnCache;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes {@link WeatherEffectRenderer#extractRenderState} during rain and snowstorms:
 * <ol>
 *   <li>Mathematically rejects weather columns strictly behind the camera near-plane before
 *       running any chunk heightmap, 3D biome Voronoi precipitation, lightmap, or
 *       {@code ColumnInstance} allocation.</li>
 *   <li>Caches {@code (height, precipitation, lightCoords)} metadata for outer columns
 *       ({@code |dx| > 2 || |dz| > 2}) in a 2048-slot primitive {@link WeatherColumnCache} with
 *       staggered TTL expiration while refreshing the immediate 5x5 area live every frame.</li>
 * </ol>
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	@Shadow
	private WeatherEffectRenderer.ColumnInstance createRainColumnInstance(
		RandomSource random,
		long gameTime,
		int x,
		int bottomY,
		int topY,
		int z,
		int lightCoords,
		float partialTick
	) {
		throw new AssertionError();
	}

	@Shadow
	private WeatherEffectRenderer.ColumnInstance createSnowColumnInstance(
		RandomSource random,
		long gameTime,
		int x,
		int bottomY,
		int topY,
		int z,
		int lightCoords,
		float partialTick
	) {
		throw new AssertionError();
	}

	@Unique
	private BlockPos.MutableBlockPos silicium$weatherPos;
	@Unique
	private WeatherColumnCache silicium$columnCache;
	@Unique
	private ClientLevel silicium$lastWeatherLevel;

	@Inject(method = "<init>()V", at = @At("RETURN"))
	private void silicium$initWeatherCache(CallbackInfo ci) {
		this.silicium$ensureWeatherCache();
	}

	@Unique
	private void silicium$ensureWeatherCache() {
		if (this.silicium$weatherPos == null) {
			this.silicium$weatherPos = new BlockPos.MutableBlockPos();
		}
		if (this.silicium$columnCache == null) {
			this.silicium$columnCache = new WeatherColumnCache(2048);
		}
	}

	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void silicium$fastExtractWeather(
		ClientLevel level,
		float partialTick,
		Vec3 cameraPos,
		WeatherRenderState state,
		CallbackInfo ci
	) {
		ci.cancel();

		state.intensity = level.getRainLevel(partialTick);
		if (state.intensity <= 0.0f) {
			return;
		}
		this.silicium$ensureWeatherCache();

		Minecraft mc = Minecraft.getInstance();
		int radius = mc.options.weatherRadius().get();
		state.radius = radius;

		if (level != this.silicium$lastWeatherLevel) {
			this.silicium$columnCache.clear();
			this.silicium$lastWeatherLevel = level;
		}

		double camPosX = cameraPos.x();
		double camPosY = cameraPos.y();
		double camPosZ = cameraPos.z();
		int camX = Mth.floor(camPosX);
		int camY = Mth.floor(camPosY);
		int camZ = Mth.floor(camPosZ);

		boolean cullBehind = SiliciumConfig.get().cullWeatherBehindCamera;
		float fwdX = 0.0f;
		float fwdY = 0.0f;
		float fwdZ = 0.0f;
		double bestPossibleYTerm = 0.0;
		if (cullBehind && mc.gameRenderer != null) {
			Camera mainCam = mc.gameRenderer.mainCamera();
			if (mainCam != null && mainCam.position().distanceToSqr(cameraPos) < 1e-4) {
				Vector3fc fwd = mainCam.forwardVector();
				fwdX = fwd.x();
				fwdY = fwd.y();
				fwdZ = fwd.z();
				double yExtreme = fwdY >= 0.0f ? (camY + radius - camPosY) : (camY - radius - camPosY);
				bestPossibleYTerm = yExtreme * fwdY;
			} else {
				cullBehind = false;
			}
		} else {
			cullBehind = false;
		}

		long frame = SiliciumClientState.frameCounter();
		long gameTime = level.getGameTime();
		RandomSource random = RandomSource.createThreadLocalInstance();
		int skipped = 0;

		for (int z = camZ - radius; z <= camZ + radius; z++) {
			for (int x = camX - radius; x <= camX + radius; x++) {
				boolean immediate = Math.abs(x - camX) <= 2 && Math.abs(z - camZ) <= 2;

				// Every rendered column lies strictly within [camY - radius, camY + radius];
				// if its closest corner to the forward plane has dot < -2.0 it is behind the camera.
				if (cullBehind && !immediate) {
					double relX = (x + 0.5) - camPosX;
					double relZ = (z + 0.5) - camPosZ;
					if ((relX * fwdX + bestPossibleYTerm + relZ * fwdZ) < -2.0) {
						skipped++;
						continue;
					}
				}

				int slot = immediate ? -1 : this.silicium$columnCache.slot(x, z);
				int height;
				byte precipCode;
				int lightCoords;

				if (!immediate && this.silicium$columnCache.isValid(slot, x, z, camY, frame)) {
					height = this.silicium$columnCache.heightAt(slot);
					precipCode = this.silicium$columnCache.precipAt(slot);
					lightCoords = this.silicium$columnCache.lightAt(slot);
					skipped++;
				} else {
					height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
					int preMinY = Math.max(camY - radius, height);
					int preMaxY = Math.max(camY + radius, height);
					if (preMaxY - preMinY == 0) {
						if (!immediate) {
							this.silicium$columnCache.put(slot, x, z, camY, height, WeatherColumnCache.PRECIP_NONE, 0, frame);
						}
						continue;
					}

					Biome.Precipitation precip = level.getPrecipitationAt(this.silicium$weatherPos.set(x, camY, z));
					if (precip == Biome.Precipitation.RAIN) {
						precipCode = WeatherColumnCache.PRECIP_RAIN;
					} else if (precip == Biome.Precipitation.SNOW) {
						precipCode = WeatherColumnCache.PRECIP_SNOW;
					} else {
						if (!immediate) {
							this.silicium$columnCache.put(slot, x, z, camY, height, WeatherColumnCache.PRECIP_NONE, 0, frame);
						}
						continue;
					}

					int lightY = Math.max(camY, height);
					lightCoords = LightCoordsUtil.getLightCoords(level, this.silicium$weatherPos.set(x, lightY, z));
					if (!immediate) {
						this.silicium$columnCache.put(slot, x, z, camY, height, precipCode, lightCoords, frame);
					}
				}

				int minY = Math.max(camY - radius, height);
				int maxY = Math.max(camY + radius, height);
				if (maxY - minY == 0 || precipCode == WeatherColumnCache.PRECIP_NONE) {
					continue;
				}

				int seed = x * x * 3121 + x * 45238971 ^ z * z * 418711 + z * 13761;
				random.setSeed(seed);
				if (precipCode == WeatherColumnCache.PRECIP_RAIN) {
					state.rainColumns.add(createRainColumnInstance(random, gameTime, x, minY, maxY, z, lightCoords, partialTick));
				} else if (precipCode == WeatherColumnCache.PRECIP_SNOW) {
					state.snowColumns.add(createSnowColumnInstance(random, gameTime, x, minY, maxY, z, lightCoords, partialTick));
				}
			}
		}

		SiliciumMetrics.add(SiliciumMetrics.Counter.WEATHER_COLUMNS_SKIPPED, skipped);
	}
}
