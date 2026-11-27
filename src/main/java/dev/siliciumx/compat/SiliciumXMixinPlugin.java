package dev.siliciumx.compat;

import dev.siliciumx.module.SiliciumXModule;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared Mixin config plugin for both common ({@code siliciumx.mixins.json}) and client
 * ({@code siliciumx.client.mixins.json}) mixin configurations.
 *
 * <p>Gates every optimization mixin against {@link ModCompatibility} at class-load time so
 * disabled or auto-yielded modules inject zero bytecode and incur zero runtime overhead.</p>
 */
public final class SiliciumXMixinPlugin implements IMixinConfigPlugin {
	private static final Map<String, SiliciumXModule> MIXIN_TO_MODULE = Map.ofEntries(
		// Common core telemetry
		Map.entry("dev.siliciumx.mixin.core.ServerTickMetricsMixin", SiliciumXModule.DIAGNOSTICS_TELEMETRY),

		// Common CPU modules
		Map.entry("dev.siliciumx.mixin.math.FastSineLutMixin", SiliciumXModule.CPU_FAST_SINE_LUT),
		Map.entry("dev.siliciumx.mixin.biome.BiomeManagerMixin", SiliciumXModule.CPU_FAST_BIOME_VORONOI),
		Map.entry("dev.siliciumx.mixin.worldgen.GradientNoiseAccessor", SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.siliciumx.mixin.worldgen.GradientNoiseMixin", SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.siliciumx.mixin.worldgen.PerlinNoiseMixin", SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE),
		Map.entry("dev.siliciumx.mixin.chunk.ServerChunkCacheMixin", SiliciumXModule.CPU_CHUNK_LOOKUP_CACHE),
		Map.entry("dev.siliciumx.mixin.occlusion.BlockOcclusionCacheMixin", SiliciumXModule.CPU_OCCLUSION_SHAPE_CACHE),
		Map.entry("dev.siliciumx.mixin.occlusion.ShapesFaceOcclusionMixin", SiliciumXModule.CPU_FACE_OCCLUSION_CACHE),
		Map.entry("dev.siliciumx.mixin.entity.AttributeInstanceMixin", SiliciumXModule.CPU_ATTRIBUTE_FAST_CALC),
		Map.entry("dev.siliciumx.mixin.ai.WalkNodeEvaluatorMixin", SiliciumXModule.CPU_PATHFINDING_ALLOC),
		Map.entry("dev.siliciumx.mixin.hopper.HopperBlockEntityMixin", SiliciumXModule.CPU_HOPPER_FAST_SCAN),
		Map.entry("dev.siliciumx.mixin.cpu.UtilThreadTuningMixin", SiliciumXModule.CPU_THREAD_POOL_TUNING),
		Map.entry("dev.siliciumx.mixin.startup.DataFixersStartupMixin", SiliciumXModule.STARTUP_LAZY_DATAFIXER),

		// Common Memory modules
		Map.entry("dev.siliciumx.mixin.memory.BlockStateCacheAccessor", SiliciumXModule.MEMORY_BLOCKSTATE_CACHE_DEDUP),
		Map.entry("dev.siliciumx.mixin.memory.BlockStateBaseCacheMixin", SiliciumXModule.MEMORY_BLOCKSTATE_CACHE_DEDUP),
		Map.entry("dev.siliciumx.mixin.memory.CompoundTagKeyDedupMixin", SiliciumXModule.MEMORY_NBT_KEY_DEDUP),
		Map.entry("dev.siliciumx.mixin.memory.StringTagDedupMixin", SiliciumXModule.MEMORY_NBT_STRING_DEDUP),

		// Client GPU, Render & Audio modules (Sodium-friendly)
		// Note: ClientFrameMetricsMixin is always active so SiliciumXClientState.advanceFrame()
		// keeps ticking even when diagnostics.telemetry is disabled; telemetry sampling itself is gated inside the mixin.
		Map.entry("dev.siliciumx.client.mixin.lightmap.LightmapCacheMixin", SiliciumXModule.GPU_LIGHTMAP_CACHE),
		Map.entry("dev.siliciumx.client.mixin.particle.ParticleAccessor", SiliciumXModule.GPU_PARTICLE_CULLING),
		Map.entry("dev.siliciumx.client.mixin.particle.QuadParticleCullingMixin", SiliciumXModule.GPU_PARTICLE_CULLING),
		Map.entry("dev.siliciumx.client.mixin.particle.ParticleSpawnOptMixin", SiliciumXModule.CLIENT_PARTICLE_SPAWN_PHYSICS),
		Map.entry("dev.siliciumx.client.mixin.particle.ParticlePhysicsOptMixin", SiliciumXModule.CLIENT_PARTICLE_SPAWN_PHYSICS),
		Map.entry("dev.siliciumx.client.mixin.particle.ClientAnimateTickMixin", SiliciumXModule.CLIENT_ANIMATE_TICK_OPT),
		Map.entry("dev.siliciumx.client.mixin.cull.EntityOcclusionMixin", SiliciumXModule.GPU_ENTITY_OCCLUSION_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.BlockEntityOcclusionMixin", SiliciumXModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.SignTextCullingMixin", SiliciumXModule.GPU_SIGN_AND_SHADOW_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.EntityShadowOptMixin", SiliciumXModule.GPU_SIGN_AND_SHADOW_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.EntityNameTagCullingMixin", SiliciumXModule.GPU_NAMETAG_AND_LAYER_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.CapeLayerCullingMixin", SiliciumXModule.GPU_NAMETAG_AND_LAYER_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.ItemInHandLayerCullingMixin", SiliciumXModule.GPU_NAMETAG_AND_LAYER_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.HumanoidArmorLayerCullingMixin", SiliciumXModule.GPU_NAMETAG_AND_LAYER_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.ItemLayerStateAccessor", SiliciumXModule.GPU_ITEM_GLINT_AND_LOD_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.ItemStackRenderStateMixin", SiliciumXModule.GPU_ITEM_GLINT_AND_LOD_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.ItemEntityOptMixin", SiliciumXModule.GPU_ITEM_GLINT_AND_LOD_CULLING),
		Map.entry("dev.siliciumx.client.mixin.cull.ItemFrameOptMixin", SiliciumXModule.GPU_ITEM_GLINT_AND_LOD_CULLING),
		Map.entry("dev.siliciumx.client.mixin.render.WeatherEffectRendererMixin", SiliciumXModule.GPU_WEATHER_CULLING),
		Map.entry("dev.siliciumx.client.mixin.hud.DebugScreenOverlayMixin", SiliciumXModule.GPU_HUD_DEBUG_CACHE),
		Map.entry("dev.siliciumx.client.mixin.hud.ScoreboardChangeTrackerMixin", SiliciumXModule.GPU_HUD_SCOREBOARD_TAB_CACHE),
		Map.entry("dev.siliciumx.client.mixin.hud.ScoreboardSidebarCacheMixin", SiliciumXModule.GPU_HUD_SCOREBOARD_TAB_CACHE),
		Map.entry("dev.siliciumx.client.mixin.hud.PlayerTabOverlayCacheMixin", SiliciumXModule.GPU_HUD_SCOREBOARD_TAB_CACHE),
		Map.entry("dev.siliciumx.client.mixin.hud.StringSplitterWidthCacheMixin", SiliciumXModule.GPU_TEXT_WIDTH_CACHE),
		Map.entry("dev.siliciumx.client.mixin.hud.RichTextWidthCacheMixin", SiliciumXModule.GPU_RICH_TEXT_WIDTH_CACHE),
		Map.entry("dev.siliciumx.client.mixin.sound.SoundEngineOptMixin", SiliciumXModule.CLIENT_SOUND_ENGINE_OPT)
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
		if ("dev.siliciumx.client.mixin.hud.FontManagerReloadMixin".equals(mixinClassName)) {
			return compat.isModuleActive(SiliciumXModule.GPU_TEXT_WIDTH_CACHE)
				|| compat.isModuleActive(SiliciumXModule.GPU_RICH_TEXT_WIDTH_CACHE)
				|| compat.isModuleActive(SiliciumXModule.GPU_HUD_SCOREBOARD_TAB_CACHE);
		}
		SiliciumXModule module = MIXIN_TO_MODULE.get(mixinClassName);
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
