package dev.silicium.compat;

import dev.silicium.module.SiliciumModule;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared Mixin config plugin for both common ({@code silicium.mixins.json}) and client
 * ({@code silicium.client.mixins.json}) mixin configurations.
 *
 * <p>Gates every optimization mixin against {@link ModCompatibility} at class-load time so
 * disabled or auto-yielded modules inject zero bytecode and incur zero runtime overhead.</p>
 */
public final class SiliciumMixinPlugin implements IMixinConfigPlugin {
	private static final Map<String, SiliciumModule> MIXIN_TO_MODULE = Map.ofEntries(
		// Common core telemetry
		Map.entry("dev.silicium.mixin.core.ServerTickMetricsMixin", SiliciumModule.DIAGNOSTICS_TELEMETRY),

		// Common CPU modules
		Map.entry("dev.silicium.mixin.math.FastSineLutMixin", SiliciumModule.CPU_FAST_SINE_LUT),
		Map.entry("dev.silicium.mixin.biome.BiomeManagerMixin", SiliciumModule.CPU_FAST_BIOME_VORONOI),
		Map.entry("dev.silicium.mixin.worldgen.GradientNoiseAccessor", SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.silicium.mixin.worldgen.GradientNoiseMixin", SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.silicium.mixin.worldgen.PerlinNoiseMixin", SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.silicium.mixin.chunk.ServerChunkCacheMixin", SiliciumModule.CPU_CHUNK_LOOKUP_CACHE),
		Map.entry("dev.silicium.mixin.occlusion.BlockOcclusionCacheMixin", SiliciumModule.CPU_OCCLUSION_SHAPE_CACHE),
		Map.entry("dev.silicium.mixin.occlusion.ShapesFaceOcclusionMixin", SiliciumModule.CPU_FACE_OCCLUSION_CACHE),
		Map.entry("dev.silicium.mixin.entity.AttributeInstanceMixin", SiliciumModule.CPU_ATTRIBUTE_FAST_CALC),
		Map.entry("dev.silicium.mixin.ai.WalkNodeEvaluatorMixin", SiliciumModule.CPU_PATHFINDING_ALLOC),
		Map.entry("dev.silicium.mixin.hopper.HopperBlockEntityMixin", SiliciumModule.CPU_HOPPER_FAST_SCAN),

		// Common Memory modules
		Map.entry("dev.silicium.mixin.memory.BlockStateCacheAccessor", SiliciumModule.MEMORY_BLOCKSTATE_CACHE_DEDUP),
		Map.entry("dev.silicium.mixin.memory.BlockStateBaseCacheMixin", SiliciumModule.MEMORY_BLOCKSTATE_CACHE_DEDUP),
		Map.entry("dev.silicium.mixin.memory.CompoundTagKeyDedupMixin", SiliciumModule.MEMORY_NBT_KEY_DEDUP),
		Map.entry("dev.silicium.mixin.memory.StringTagDedupMixin", SiliciumModule.MEMORY_NBT_STRING_DEDUP),

		// Client GPU, Render & Audio modules (Sodium-friendly)
		// Note: ClientFrameMetricsMixin is always active so SiliciumClientState.advanceFrame()
		// keeps ticking even when diagnostics.telemetry is disabled; telemetry sampling itself is gated inside the mixin.
		Map.entry("dev.silicium.client.mixin.lightmap.LightmapCacheMixin", SiliciumModule.GPU_LIGHTMAP_CACHE),
		Map.entry("dev.silicium.client.mixin.particle.ParticleAccessor", SiliciumModule.GPU_PARTICLE_CULLING),
		Map.entry("dev.silicium.client.mixin.particle.QuadParticleCullingMixin", SiliciumModule.GPU_PARTICLE_CULLING),
		Map.entry("dev.silicium.client.mixin.particle.ParticleSpawnOptMixin", SiliciumModule.CLIENT_PARTICLE_SPAWN_PHYSICS),
		Map.entry("dev.silicium.client.mixin.particle.ParticlePhysicsOptMixin", SiliciumModule.CLIENT_PARTICLE_SPAWN_PHYSICS),
		Map.entry("dev.silicium.client.mixin.particle.ClientAnimateTickMixin", SiliciumModule.CLIENT_ANIMATE_TICK_OPT),
		Map.entry("dev.silicium.client.mixin.cull.EntityOcclusionMixin", SiliciumModule.GPU_ENTITY_OCCLUSION_CULLING),
		Map.entry("dev.silicium.client.mixin.cull.BlockEntityOcclusionMixin", SiliciumModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING),
		Map.entry("dev.silicium.client.mixin.cull.SignTextCullingMixin", SiliciumModule.GPU_SIGN_AND_SHADOW_CULLING),
		Map.entry("dev.silicium.client.mixin.cull.EntityShadowOptMixin", SiliciumModule.GPU_SIGN_AND_SHADOW_CULLING),
		Map.entry("dev.silicium.client.mixin.render.WeatherEffectRendererMixin", SiliciumModule.GPU_WEATHER_CULLING),
		Map.entry("dev.silicium.client.mixin.hud.DebugScreenOverlayMixin", SiliciumModule.GPU_HUD_DEBUG_CACHE),
		Map.entry("dev.silicium.client.mixin.hud.StringSplitterWidthCacheMixin", SiliciumModule.GPU_TEXT_WIDTH_CACHE),
		Map.entry("dev.silicium.client.mixin.hud.RichTextWidthCacheMixin", SiliciumModule.GPU_RICH_TEXT_WIDTH_CACHE),
		Map.entry("dev.silicium.client.mixin.sound.SoundEngineOptMixin", SiliciumModule.CLIENT_SOUND_ENGINE_OPT)
	);

	private ModCompatibility compatibility;

	@Override
	public void onLoad(String mixinPackage) {
		this.compatibility = ModCompatibility.get();
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		ModCompatibility compat = this.compatibility != null ? this.compatibility : ModCompatibility.get();
		if ("dev.silicium.client.mixin.hud.FontManagerReloadMixin".equals(mixinClassName)) {
			return compat.isModuleActive(SiliciumModule.GPU_TEXT_WIDTH_CACHE)
				|| compat.isModuleActive(SiliciumModule.GPU_RICH_TEXT_WIDTH_CACHE);
		}
		SiliciumModule module = MIXIN_TO_MODULE.get(mixinClassName);
		if (module == null) {
			return true;
		}
		return compat.isModuleActive(module);
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
