package dev.silicium.compat;

import dev.silicium.SiliciumConstants;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.module.SiliciumModule;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Precision Universal Compatibility Engine for Silicium.
 *
 * <p>Detects companion optimization mods at Mixin-load time and resolves each module using exact
 * method-level conflict rules, per-module {@code "auto" | "force" | "off"} modes, and cooperative
 * companion config inspection (e.g. stepping in automatically when a rule is disabled in
 * {@code config/lithium.properties}).</p>
 */
public final class ModCompatibility {
	public static final Map<String, KnownMod> KNOWN_ECOSYSTEM_MODS;

	static {
		Map<String, KnownMod> map = new LinkedHashMap<>();
		register(map, "sodium", "Sodium", "Chunk renderer & GPU pipeline (complementary — 0 conflicts)");
		register(map, "lithium", "Lithium", "Server/client tick, AI, physics, chunk & math optimizations");
		register(map, "ferritecore", "FerriteCore", "BlockState & model memory deduplication");
		register(map, "modernfix", "ModernFix", "Dynamic resources, memory & startup optimizations");
		register(map, "immediatelyfast", "ImmediatelyFast", "Immediate-mode HUD/GUI/entity batching");
		register(map, "c2me", "C2ME", "Concurrent chunk management & worldgen");
		register(map, "entityculling", "EntityCulling", "Async path-traced entity & block entity culling");
		register(map, "moreculling", "MoreCulling", "Block, leaf, sign & particle culling");
		register(map, "iris", "Iris Shaders", "Shader pipeline (yields occlusion culling for shadow passes)");
		register(map, "oculus", "Oculus", "Shader pipeline (yields occlusion culling for shadow passes)");
		register(map, "badoptimizations", "BadOptimizations", "Lightmap, F3 HUD & client micro-optimizations");
		register(map, "krypton", "Krypton", "Netty network stack optimizations (complementary)");
		register(map, "kryptonplus", "KryptonPlus", "Enhanced Netty network stack optimizations (complementary)");
		register(map, "vmp", "Very Many Players", "High-player-count chunk & entity tracking");
		register(map, "noisium", "Noisium", "Worldgen noise & biome optimizations");
		register(map, "scalablelux", "ScalableLux", "Multithreaded light engine (complementary)");
		register(map, "starlight", "Starlight", "Rewritten light engine (complementary)");
		register(map, "particle_core", "Particle Core", "Particle culling & reduction");
		register(map, "smart_particles", "Smart Particles", "Particle engine optimization (complementary)");
		register(map, "betterblockentities", "Better Block Entities", "Block entity chunk-meshing pipeline");
		register(map, "dynamic_fps", "Dynamic FPS", "Background/idle framerate throttling (complementary)");
		register(map, "reflex", "Reflex AntiLag", "Client frame pacing & debug tweaks (complementary)");
		register(map, "resourcetrimmer", "ResourceTrimmer", "Resource & asset memory trimming (complementary)");
		register(map, "exordium", "Exordium", "GUI/HUD framerate throttling");
		register(map, "alternate-current", "Alternate Current", "Redstone dust network optimization (complementary)");
		register(map, "vulkanmod", "VulkanMod", "Vulkan rendering engine (complementary)");
		register(map, "distanthorizons", "Distant Horizons", "LOD terrain renderer (complementary)");
		register(map, "nvidium", "Nvidium", "Mesh-shader terrain renderer (complementary)");
		register(map, "sound_physics_remastered", "Sound Physics Remastered", "Acoustic raytracing & reverb sound engine");
		register(map, "fastmath", "FastMath", "Alternative sine/cosine LUT");
		KNOWN_ECOSYSTEM_MODS = Collections.unmodifiableMap(map);
	}

	private static void register(Map<String, KnownMod> map, String id, String displayName, String domain) {
		map.put(id, new KnownMod(id, displayName, domain));
	}

	private static volatile ModCompatibility INSTANCE;

	private final Map<SiliciumModule, ModuleResolution> resolutions;
	private final List<KnownMod> detectedMods;
	private final boolean autoCompatEnabled;

	public ModCompatibility(SiliciumConfig config, Predicate<String> modLoadedPredicate) {
		this(config, modLoadedPredicate, CompanionConfigReader.empty());
	}

	public ModCompatibility(
		SiliciumConfig config,
		Predicate<String> modLoadedPredicate,
		CompanionConfigReader companionConfigs
	) {
		this.autoCompatEnabled = config.autoCompatibilityMode;

		List<KnownMod> detected = new ArrayList<>();
		for (KnownMod known : KNOWN_ECOSYSTEM_MODS.values()) {
			if (modLoadedPredicate.test(known.modId())) {
				detected.add(known);
			}
		}
		this.detectedMods = Collections.unmodifiableList(detected);

		Map<SiliciumModule, ModuleResolution> map = new EnumMap<>(SiliciumModule.class);
		for (SiliciumModule module : SiliciumModule.values()) {
			map.put(module, resolveModule(module, config, modLoadedPredicate, companionConfigs));
		}
		this.resolutions = Collections.unmodifiableMap(map);
	}

	public static ModCompatibility get() {
		ModCompatibility current = INSTANCE;
		if (current == null) {
			synchronized (ModCompatibility.class) {
				current = INSTANCE;
				if (current == null) {
					FabricLoader loader = FabricLoader.getInstance();
					SiliciumConfig cfg = SiliciumConfig.get();
					CompanionConfigReader companion = cfg.respectCompanionConfigs
						? CompanionConfigReader.fromConfigDir(loader.getConfigDir())
						: CompanionConfigReader.empty();
					current = new ModCompatibility(cfg, loader::isModLoaded, companion);
					INSTANCE = current;
				}
			}
		}
		return current;
	}

	private static ModuleResolution resolveModule(
		SiliciumModule module,
		SiliciumConfig config,
		Predicate<String> modLoadedPredicate,
		CompanionConfigReader companionConfigs
	) {
		SiliciumModule.Mode mode = config.moduleMode(module);
		if (mode == SiliciumModule.Mode.OFF) {
			return new ModuleResolution(module, State.DISABLED_BY_CONFIG, null, null);
		}
		if (mode == SiliciumModule.Mode.FORCE) {
			return new ModuleResolution(module, State.FORCED_ACTIVE, null, "forced via config");
		}
		if (config.autoCompatibilityMode) {
			String takenOverNote = null;
			for (String conflictModId : module.yieldMods()) {
				if (!modLoadedPredicate.test(conflictModId)) {
					continue;
				}
				// Cooperative check: if Lithium is installed but the user disabled its rule in
				// config/lithium.properties, Silicium automatically steps in to cover the gap.
				if ("lithium".equals(conflictModId)
					&& config.respectCompanionConfigs
					&& module.lithiumRuleKey() != null
					&& companionConfigs.isLithiumRuleDisabled(module.lithiumRuleKey())
				) {
					takenOverNote = "lithium rule disabled (" + module.lithiumRuleKey() + "=false)";
					continue;
				}
				return new ModuleResolution(module, State.YIELDED_TO_MOD, conflictModId, null);
			}
			return new ModuleResolution(module, State.ACTIVE, null, takenOverNote);
		}
		return new ModuleResolution(module, State.ACTIVE, null, null);
	}

	public boolean isModuleActive(SiliciumModule module) {
		ModuleResolution resolution = this.resolutions.get(module);
		return resolution != null && resolution.isEnabled();
	}

	public ModuleResolution resolution(SiliciumModule module) {
		return this.resolutions.get(module);
	}

	public Map<SiliciumModule, ModuleResolution> allResolutions() {
		return this.resolutions;
	}

	public List<KnownMod> detectedMods() {
		return this.detectedMods;
	}

	public boolean isAutoCompatEnabled() {
		return this.autoCompatEnabled;
	}

	public boolean isModDetected(String modId) {
		for (KnownMod mod : this.detectedMods) {
			if (mod.modId().equals(modId)) {
				return true;
			}
		}
		return false;
	}

	public int countActive() {
		int count = 0;
		for (ModuleResolution r : this.resolutions.values()) {
			if (r.isEnabled()) {
				count++;
			}
		}
		return count;
	}

	public int countByState(State state) {
		int count = 0;
		for (ModuleResolution r : this.resolutions.values()) {
			if (r.state() == state) {
				count++;
			}
		}
		return count;
	}

	public void logStartupReport() {
		int active = countActive();
		int forced = countByState(State.FORCED_ACTIVE);
		int yielded = countByState(State.YIELDED_TO_MOD);
		int disabled = countByState(State.DISABLED_BY_CONFIG);

		SiliciumConstants.LOGGER.info(
			"Silicium initialized: {} active ({} forced), {} auto-yielded, {} disabled (autoCompat={})",
			active,
			forced,
			yielded,
			disabled,
			this.autoCompatEnabled
		);

		if (!SiliciumConfig.get().logCompatibilityReport) {
			return;
		}

		if (this.detectedMods.isEmpty()) {
			SiliciumConstants.LOGGER.info("Ecosystem scan: standalone mode (no companion optimization mods detected).");
		} else {
			StringBuilder sb = new StringBuilder("Ecosystem scan: detected ");
			for (int i = 0; i < this.detectedMods.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(this.detectedMods.get(i).displayName());
			}
			SiliciumConstants.LOGGER.info(sb.toString());
			if (isModDetected("sodium")) {
				SiliciumConstants.LOGGER.info("Sodium detected -> Silicium running in Sodium-friendly complementary mode (0 chunk-engine conflicts).");
			}
		}

		for (ModuleResolution r : this.resolutions.values()) {
			if (r.state() == State.YIELDED_TO_MOD) {
				SiliciumConstants.LOGGER.info(
					"  [YIELD] {} -> deferred to '{}' (exact conflict match)",
					r.module().id(),
					r.yieldedToMod()
				);
			} else if (r.state() == State.FORCED_ACTIVE) {
				SiliciumConstants.LOGGER.info(
					"  [FORCE] {} -> forced active via config",
					r.module().id()
				);
			} else if (r.note() != null) {
				SiliciumConstants.LOGGER.info(
					"  [TAKEOVER] {} -> active ({})",
					r.module().id(),
					r.note()
				);
			}
		}
	}

	public enum State {
		ACTIVE,
		FORCED_ACTIVE,
		YIELDED_TO_MOD,
		DISABLED_BY_CONFIG
	}

	public record KnownMod(String modId, String displayName, String domain) {
	}

	public record ModuleResolution(SiliciumModule module, State state, String yieldedToMod, String note) {
		public boolean isEnabled() {
			return this.state == State.ACTIVE || this.state == State.FORCED_ACTIVE;
		}
	}
}
