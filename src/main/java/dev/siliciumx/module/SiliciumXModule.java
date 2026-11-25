package dev.siliciumx.module;

import java.util.List;

/**
 * Registry of all independent SiliciumX optimization modules.
 *
 * <p>Each module supports three modes in {@code config/siliciumx.json}:
 * {@code "auto"} (enabled unless an exact conflicting companion optimization is active),
 * {@code "force"} (always active regardless of companion mods), or {@code "off"} (disabled).</p>
 */
public enum SiliciumXModule {
	CPU_FAST_SINE_LUT(
		"cpu.fast_sine_lut",
		Category.CPU,
		Environment.COMMON,
		List.of("lithium", "fastmath"),
		"mixin.math.sine_lut",
		"4x smaller quarter-wave sine/cos LUT (64 KB vs 256 KB) for L1/L2 CPU cache residency"
	),
	CPU_FAST_BIOME_VORONOI(
		"cpu.fast_biome_voronoi",
		Category.CPU,
		Environment.COMMON,
		List.of("c2me", "noisium"),
		null,
		"Bit-identical 3D Voronoi biome sampling with exact mathematical lower-bound corner pruning"
	),
	CPU_CHUNK_LOOKUP_CACHE(
		"cpu.chunk_lookup_cache",
		Category.CPU,
		Environment.COMMON,
		List.of("lithium", "c2me", "vmp"),
		"mixin.world.chunk_access",
		"Zero-allocation 128-slot 2-way associative FULL chunk cache on ServerChunkCache"
	),
	CPU_OCCLUSION_SHAPE_CACHE(
		"cpu.occlusion_shape_cache",
		Category.CPU,
		Environment.COMMON,
		List.of("moreculling"),
		null,
		"Zero-allocation ThreadLocal identity cache eliminating ShapePairKey allocations in Block.shouldRenderFace"
	),
	CPU_FACE_OCCLUSION_CACHE(
		"cpu.face_occlusion_cache",
		Category.CPU,
		Environment.COMMON,
		List.of(),
		null,
		"Zero-allocation ThreadLocal identity cache avoiding joinUnoptimized shape allocations in Shapes.faceShapeOccludes"
	),
	CPU_ATTRIBUTE_FAST_CALC(
		"cpu.attribute_fast_calc",
		Category.CPU,
		Environment.COMMON,
		List.of(),
		null,
		"Fast-paths AttributeInstance.calculateValue() when no modifiers are present, skipping 3 EnumMap/iterator passes"
	),
	CPU_PATHFINDING_ALLOC(
		"cpu.pathfinding_alloc",
		Category.CPU,
		Environment.COMMON,
		List.of("lithium"),
		"mixin.ai.pathing",
		"Eliminates per-node lambda, EnumSet, and Iterator allocations in WalkNodeEvaluator mob pathfinding"
	),
	CPU_HOPPER_FAST_SCAN(
		"cpu.hopper_fast_scan",
		Category.CPU,
		Environment.COMMON,
		List.of("lithium"),
		"mixin.block.hopper",
		"Removes hopper iterator churn and fast-paths empty source container scans while preserving vanilla timing"
	),
	MEMORY_BLOCKSTATE_CACHE_DEDUP(
		"memory.blockstate_cache_dedup",
		Category.MEMORY,
		Environment.COMMON,
		List.of("ferritecore"),
		null,
		"Deduplicates immutable BlockStateBase$Cache objects and per-face occlusion shape arrays across block states"
	),
	MEMORY_NBT_KEY_DEDUP(
		"memory.nbt_key_dedup",
		Category.MEMORY,
		Environment.COMMON,
		List.of(),
		null,
		"Lock-free bounded canonicalization of repeated NBT CompoundTag key strings during disk/network loads"
	),
	MEMORY_NBT_STRING_DEDUP(
		"memory.nbt_string_dedup",
		Category.MEMORY,
		Environment.COMMON,
		List.of(),
		null,
		"Lock-free bounded canonical pool for short immutable NBT StringTag instances (block palettes, IDs, properties)"
	),
	GPU_LIGHTMAP_CACHE(
		"gpu.lightmap_cache",
		Category.GPU,
		Environment.CLIENT,
		List.of("badoptimizations"),
		null,
		"Skips redundant GPU lightmap UBO maps and render passes when lighting state change is sub-LSB (< 1/255)"
	),
	GPU_PARTICLE_CULLING(
		"gpu.particle_culling",
		Category.GPU,
		Environment.CLIENT,
		List.of("particle_core", "moreculling"),
		null,
		"Sodium-friendly distance & solid-block interior culling during QuadParticleGroup state extraction"
	),
	CLIENT_PARTICLE_SPAWN_PHYSICS(
		"client.particle_spawn_physics",
		Category.GPU,
		Environment.CLIENT,
		List.of("particle_core"),
		null,
		"Rejects off-distance particle spawns before heap allocation and skips VoxelShape collision physics for distant particles"
	),
	GPU_ENTITY_OCCLUSION_CULLING(
		"gpu.entity_occlusion_culling",
		Category.GPU,
		Environment.CLIENT,
		List.of("entityculling", "moreculling", "iris", "oculus"),
		null,
		"Conservative 9-point (8 corners + center) DDA solid-block occlusion culling for entities"
	),
	GPU_BLOCK_ENTITY_OCCLUSION_CULLING(
		"gpu.block_entity_occlusion_culling",
		Category.GPU,
		Environment.CLIENT,
		List.of("entityculling", "moreculling", "betterblockentities", "iris", "oculus"),
		null,
		"Conservative 9-point DDA solid-block occlusion culling for block entities (skips off-screen renderers)"
	),
	GPU_SIGN_AND_SHADOW_CULLING(
		"gpu.sign_and_shadow_culling",
		Category.GPU,
		Environment.CLIENT,
		List.of("moreculling", "betterblockentities"),
		null,
		"Eliminates blank, back-facing, and distant sign text passes and optimizes entity shadow extraction"
	),
	GPU_WEATHER_CULLING(
		"gpu.weather_culling",
		Category.GPU,
		Environment.CLIENT,
		List.of("iris", "oculus"),
		null,
		"Caches weather column height/precipitation/light lookups and culls weather columns strictly behind the camera"
	),
	GPU_HUD_DEBUG_CACHE(
		"gpu.hud_debug_cache",
		Category.GPU,
		Environment.CLIENT,
		List.of("badoptimizations", "exordium"),
		null,
		"Throttles F3 debug overlay text rebuilding to 20 Hz (cutting F3 String/List GC) + optional SiliciumX line"
	),
	GPU_TEXT_WIDTH_CACHE(
		"gpu.text_width_cache",
		Category.GPU,
		Environment.CLIENT,
		List.of(),
		null,
		"Caches StringSplitter.stringWidth(String) per font instance (epoch-invalidated on reload), cutting HUD/UI text GC"
	),
	GPU_RICH_TEXT_WIDTH_CACHE(
		"gpu.rich_text_width_cache",
		Category.GPU,
		Environment.CLIENT,
		List.of(),
		null,
		"Caches StringSplitter.stringWidth(FormattedText) for styled literals and translations (UI, tooltips, chat, nametags)"
	),
	CLIENT_ANIMATE_TICK_OPT(
		"client.animate_tick_opt",
		Category.GPU,
		Environment.CLIENT,
		List.of("badoptimizations"),
		null,
		"Eliminates 1,334 redundant getFluidState chunk lookups per client tick in ClientLevel.doAnimateTick"
	),
	CLIENT_SOUND_ENGINE_OPT(
		"client.sound_engine_opt",
		Category.CPU,
		Environment.CLIENT,
		List.of("sound_physics_remastered"),
		null,
		"Skips out-of-range inaudible sounds before OpenAL channel blocking and caps same-tick identical sound spam"
	),
	CLIENT_ASYNC_OCCLUSION_WORKER(
		"client.async_occlusion_worker",
		Category.CPU,
		Environment.CLIENT,
		List.of("entityculling", "moreculling", "iris", "oculus"),
		null,
		"Offloads 9-point entity and block-entity DDA occlusion raycasts to lock-free multi-core background worker threads"
	),
	WORLDGEN_FAST_PERLIN_NOISE(
		"worldgen.fast_perlin_noise",
		Category.CPU,
		Environment.COMMON,
		List.of("c2me", "noisium"),
		null,
		"Bit-identical allocation-free Perlin/Gradient noise evaluation removing Gradient record indirection and virtual permute hops"
	),
	DIAGNOSTICS_TELEMETRY(
		"diagnostics.telemetry",
		Category.DIAGNOSTICS,
		Environment.COMMON,
		List.of(),
		null,
		"Zero-allocation MSPT/frame-time percentile tracker, optimization counters, and /siliciumx bench"
	);

	private final String id;
	private final Category category;
	private final Environment environment;
	private final List<String> yieldMods;
	private final String lithiumRuleKey;
	private final String description;

	SiliciumXModule(
		String id,
		Category category,
		Environment environment,
		List<String> yieldMods,
		String lithiumRuleKey,
		String description
	) {
		this.id = id;
		this.category = category;
		this.environment = environment;
		this.yieldMods = yieldMods;
		this.lithiumRuleKey = lithiumRuleKey;
		this.description = description;
	}

	public String id() {
		return this.id;
	}

	public Category category() {
		return this.category;
	}

	public Environment environment() {
		return this.environment;
	}

	public List<String> yieldMods() {
		return this.yieldMods;
	}

	/**
	 * Corresponding Lithium rule key in {@code config/lithium.properties}, if applicable.
	 * When the user disables this rule in Lithium, SiliciumX automatically steps in.
	 */
	public String lithiumRuleKey() {
		return this.lithiumRuleKey;
	}

	public String description() {
		return this.description;
	}

	public enum Category {
		CPU,
		MEMORY,
		GPU,
		DIAGNOSTICS
	}

	public enum Environment {
		COMMON,
		CLIENT
	}

	public enum Mode {
		AUTO("auto"),
		FORCE("force"),
		OFF("off");

		private final String configValue;

		Mode(String configValue) {
			this.configValue = configValue;
		}

		public String configValue() {
			return this.configValue;
		}

		public static Mode parse(String raw, Mode fallback) {
			if (raw == null) {
				return fallback;
			}
			return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
				case "auto", "true", "on", "enabled" -> AUTO;
				case "force", "forced", "always" -> FORCE;
				case "off", "false", "disabled", "none" -> OFF;
				default -> fallback;
			};
		}
	}
}
