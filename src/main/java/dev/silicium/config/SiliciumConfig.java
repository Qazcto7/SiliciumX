package dev.silicium.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.silicium.SiliciumConstants;
import dev.silicium.module.SiliciumModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modular JSON configuration for Silicium ({@code config/silicium.json}).
 *
 * <p>Each module accepts {@code "auto"} (enabled unless an exact conflicting companion optimization
 * is active), {@code "force"} (always active), or {@code "off"} (disabled). Legacy boolean entries
 * ({@code true}/{@code false}) are automatically migrated.</p>
 */
public final class SiliciumConfig {
	public static final int CURRENT_SCHEMA_VERSION = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile SiliciumConfig INSTANCE;
	private static volatile SiliciumConfig BOOT_SNAPSHOT;

	public int schemaVersion = CURRENT_SCHEMA_VERSION;
	public volatile boolean autoCompatibilityMode = true;
	public volatile boolean respectCompanionConfigs = true;
	public volatile boolean logCompatibilityReport = true;

	// Per-module mode: "auto" | "force" | "off" (restart required to change Mixin application)
	public Map<String, SiliciumModule.Mode> modules = createDefaultModulesMap();

	// Runtime-tunable GPU / Render parameters (volatile for safe cross-thread reload)
	public volatile double particleMaxRenderDistance = 64.0;
	public volatile double particlePhysicsMaxDistance = 24.0;
	public volatile boolean cullParticlesInsideOpaqueBlocks = true;
	public volatile double entityCullingMinDistance = 10.0;
	public volatile double blockEntityCullingMinDistance = 8.0;
	public volatile double signTextMaxRenderDistance = 32.0;
	public volatile double entityShadowMaxDistance = 14.0;
	public volatile boolean cullWeatherBehindCamera = true;
	public volatile int occlusionMaxRaySteps = 48;
	public volatile int occlusionCacheFrames = 3;
	public volatile float lightmapEpsilon = 0.002f;
	public volatile int debugHudUpdateIntervalMs = 50;
	public volatile boolean showF3Summary = true;
	public volatile int maxIdenticalSoundsPerTick = 4;

	// Runtime-tunable CPU / Memory parameters
	public volatile int chunkLookupCacheSize = 128;

	// Precomputed squared distance thresholds (updated in sanitize())
	private volatile double particleMaxRenderDistanceSq = 64.0 * 64.0;
	private volatile double particleSpawnMaxDistanceSq = 68.0 * 68.0;
	private volatile double particlePhysicsMaxDistanceSq = 24.0 * 24.0;
	private volatile double entityCullingMinDistanceSq = 10.0 * 10.0;
	private volatile double blockEntityCullingMinDistanceSq = 8.0 * 8.0;
	private volatile double signTextMaxRenderDistanceSq = 32.0 * 32.0;
	private volatile double entityShadowMaxDistanceSq = 14.0 * 14.0;

	public static Map<String, SiliciumModule.Mode> createDefaultModulesMap() {
		Map<String, SiliciumModule.Mode> map = new LinkedHashMap<>();
		for (SiliciumModule module : SiliciumModule.values()) {
			map.put(module.id(), SiliciumModule.Mode.AUTO);
		}
		return map;
	}

	public static SiliciumConfig get() {
		SiliciumConfig current = INSTANCE;
		if (current == null) {
			synchronized (SiliciumConfig.class) {
				current = INSTANCE;
				if (current == null) {
					current = loadFromDefaultPath();
					BOOT_SNAPSHOT = current.copy();
					INSTANCE = current;
				}
			}
		}
		return current;
	}

	/**
	 * Returns {@code true} if {@code candidate} differs from the startup configuration on any
	 * Mixin/boot-time option (module modes, auto-compat flags, or chunk cache capacity).
	 */
	public static boolean isRestartRequired(SiliciumConfig candidate) {
		SiliciumConfig boot = BOOT_SNAPSHOT;
		if (boot == null) {
			boot = get();
		}
		return boot.differsInBootOptions(candidate);
	}

	public boolean differsInBootOptions(SiliciumConfig other) {
		return this.autoCompatibilityMode != other.autoCompatibilityMode
			|| this.respectCompanionConfigs != other.respectCompanionConfigs
			|| this.chunkLookupCacheSize != other.chunkLookupCacheSize
			|| !this.modules.equals(other.modules);
	}

	public static Path defaultConfigPath() {
		try {
			Path configDir = FabricLoader.getInstance().getConfigDir();
			return configDir != null ? configDir.resolve("silicium.json") : null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	public static SiliciumConfig loadFromDefaultPath() {
		Path path = defaultConfigPath();
		if (path == null) {
			SiliciumConfig fallback = new SiliciumConfig();
			fallback.sanitize();
			return fallback;
		}
		return load(path);
	}

	public static SiliciumConfig load(Path path) {
		SiliciumConfig config = new SiliciumConfig();
		boolean needsSave = false;

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed != null && parsed.isJsonObject()) {
					needsSave = config.mergeFromJson(parsed.getAsJsonObject());
				} else {
					needsSave = true;
				}
			} catch (Exception e) {
				SiliciumConstants.LOGGER.warn(
					"Failed to parse {}, falling back to defaults: {}",
					path.getFileName(),
					e.getMessage()
				);
				needsSave = true;
			}
		} else {
			needsSave = true;
		}

		config.sanitize();
		if (needsSave) {
			save(config, path);
		}
		return config;
	}

	/**
	 * Reloads runtime-tunable settings from disk while keeping Mixin module modes consistent with boot state.
	 */
	public static synchronized SiliciumConfig reloadRuntimeSettings() {
		SiliciumConfig current = get();
		Path path = defaultConfigPath();
		if (path == null) {
			return current;
		}
		if (!Files.exists(path)) {
			save(current, path);
			return current;
		}
		SiliciumConfig fresh = load(path);
		current.particleMaxRenderDistance = fresh.particleMaxRenderDistance;
		current.particlePhysicsMaxDistance = fresh.particlePhysicsMaxDistance;
		current.cullParticlesInsideOpaqueBlocks = fresh.cullParticlesInsideOpaqueBlocks;
		current.entityCullingMinDistance = fresh.entityCullingMinDistance;
		current.blockEntityCullingMinDistance = fresh.blockEntityCullingMinDistance;
		current.signTextMaxRenderDistance = fresh.signTextMaxRenderDistance;
		current.entityShadowMaxDistance = fresh.entityShadowMaxDistance;
		current.cullWeatherBehindCamera = fresh.cullWeatherBehindCamera;
		current.occlusionMaxRaySteps = fresh.occlusionMaxRaySteps;
		current.occlusionCacheFrames = fresh.occlusionCacheFrames;
		current.lightmapEpsilon = fresh.lightmapEpsilon;
		current.debugHudUpdateIntervalMs = fresh.debugHudUpdateIntervalMs;
		current.showF3Summary = fresh.showF3Summary;
		current.maxIdenticalSoundsPerTick = fresh.maxIdenticalSoundsPerTick;
		current.sanitize();
		return current;
	}

	/**
	 * Creates a deep editable copy of this configuration for UI screens.
	 */
	public SiliciumConfig copy() {
		SiliciumConfig copy = new SiliciumConfig();
		copy.schemaVersion = this.schemaVersion;
		copy.autoCompatibilityMode = this.autoCompatibilityMode;
		copy.respectCompanionConfigs = this.respectCompanionConfigs;
		copy.logCompatibilityReport = this.logCompatibilityReport;
		copy.modules = new LinkedHashMap<>(this.modules);
		copy.particleMaxRenderDistance = this.particleMaxRenderDistance;
		copy.particlePhysicsMaxDistance = this.particlePhysicsMaxDistance;
		copy.cullParticlesInsideOpaqueBlocks = this.cullParticlesInsideOpaqueBlocks;
		copy.entityCullingMinDistance = this.entityCullingMinDistance;
		copy.blockEntityCullingMinDistance = this.blockEntityCullingMinDistance;
		copy.signTextMaxRenderDistance = this.signTextMaxRenderDistance;
		copy.entityShadowMaxDistance = this.entityShadowMaxDistance;
		copy.cullWeatherBehindCamera = this.cullWeatherBehindCamera;
		copy.occlusionMaxRaySteps = this.occlusionMaxRaySteps;
		copy.occlusionCacheFrames = this.occlusionCacheFrames;
		copy.lightmapEpsilon = this.lightmapEpsilon;
		copy.debugHudUpdateIntervalMs = this.debugHudUpdateIntervalMs;
		copy.showF3Summary = this.showF3Summary;
		copy.maxIdenticalSoundsPerTick = this.maxIdenticalSoundsPerTick;
		copy.chunkLookupCacheSize = this.chunkLookupCacheSize;
		copy.sanitize();
		return copy;
	}

	/**
	 * Applies an edited draft into the live singleton instance and persists it to {@code config/silicium.json}.
	 */
	public static synchronized void applyAndSave(SiliciumConfig draft) {
		draft.sanitize();
		SiliciumConfig live = get();

		live.autoCompatibilityMode = draft.autoCompatibilityMode;
		live.respectCompanionConfigs = draft.respectCompanionConfigs;
		live.logCompatibilityReport = draft.logCompatibilityReport;
		live.modules = new LinkedHashMap<>(draft.modules);
		live.particleMaxRenderDistance = draft.particleMaxRenderDistance;
		live.particlePhysicsMaxDistance = draft.particlePhysicsMaxDistance;
		live.cullParticlesInsideOpaqueBlocks = draft.cullParticlesInsideOpaqueBlocks;
		live.entityCullingMinDistance = draft.entityCullingMinDistance;
		live.blockEntityCullingMinDistance = draft.blockEntityCullingMinDistance;
		live.signTextMaxRenderDistance = draft.signTextMaxRenderDistance;
		live.entityShadowMaxDistance = draft.entityShadowMaxDistance;
		live.cullWeatherBehindCamera = draft.cullWeatherBehindCamera;
		live.occlusionMaxRaySteps = draft.occlusionMaxRaySteps;
		live.occlusionCacheFrames = draft.occlusionCacheFrames;
		live.lightmapEpsilon = draft.lightmapEpsilon;
		live.debugHudUpdateIntervalMs = draft.debugHudUpdateIntervalMs;
		live.showF3Summary = draft.showF3Summary;
		live.maxIdenticalSoundsPerTick = draft.maxIdenticalSoundsPerTick;
		live.chunkLookupCacheSize = draft.chunkLookupCacheSize;
		live.sanitize();

		Path path = defaultConfigPath();
		if (path != null) {
			save(live, path);
		}
	}

	public static void save(SiliciumConfig config, Path path) {
		if (path == null) {
			return;
		}
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(config.toJsonObject(), writer);
			}
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicEx) {
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(config.toJsonObject(), writer);
			} catch (IOException e) {
				SiliciumConstants.LOGGER.warn("Could not write Silicium config {}: {}", path, e.getMessage());
			}
		}
	}

	private boolean mergeFromJson(JsonObject root) {
		boolean migrated = false;

		if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != CURRENT_SCHEMA_VERSION) {
			migrated = true;
		}
		this.schemaVersion = CURRENT_SCHEMA_VERSION;

		this.autoCompatibilityMode = getBool(root, "autoCompatibilityMode", this.autoCompatibilityMode);
		if (!root.has("respectCompanionConfigs")) {
			migrated = true;
		}
		this.respectCompanionConfigs = getBool(root, "respectCompanionConfigs", this.respectCompanionConfigs);
		this.logCompatibilityReport = getBool(root, "logCompatibilityReport", this.logCompatibilityReport);

		if (root.has("modules") && root.get("modules").isJsonObject()) {
			JsonObject modulesObj = root.getAsJsonObject("modules");
			for (SiliciumModule module : SiliciumModule.values()) {
				JsonElement elem = modulesObj.get(module.id());
				if (elem != null && elem.isJsonPrimitive()) {
					JsonPrimitive prim = elem.getAsJsonPrimitive();
					if (prim.isBoolean()) {
						this.modules.put(module.id(), prim.getAsBoolean() ? SiliciumModule.Mode.AUTO : SiliciumModule.Mode.OFF);
						migrated = true;
					} else if (prim.isString()) {
						this.modules.put(module.id(), SiliciumModule.Mode.parse(prim.getAsString(), SiliciumModule.Mode.AUTO));
					} else {
						this.modules.put(module.id(), SiliciumModule.Mode.AUTO);
						migrated = true;
					}
				} else {
					this.modules.put(module.id(), SiliciumModule.Mode.AUTO);
					migrated = true;
				}
			}
		} else {
			migrated = true;
		}

		JsonObject tuning = root.has("tuning") && root.get("tuning").isJsonObject()
			? root.getAsJsonObject("tuning")
			: root;

		this.particleMaxRenderDistance = getDouble(tuning, "particleMaxRenderDistance", this.particleMaxRenderDistance);
		this.particlePhysicsMaxDistance = getDouble(tuning, "particlePhysicsMaxDistance", this.particlePhysicsMaxDistance);
		this.cullParticlesInsideOpaqueBlocks = getBool(tuning, "cullParticlesInsideOpaqueBlocks", this.cullParticlesInsideOpaqueBlocks);
		this.entityCullingMinDistance = getDouble(tuning, "entityCullingMinDistance", this.entityCullingMinDistance);
		this.blockEntityCullingMinDistance = getDouble(tuning, "blockEntityCullingMinDistance", this.blockEntityCullingMinDistance);
		this.signTextMaxRenderDistance = getDouble(tuning, "signTextMaxRenderDistance", this.signTextMaxRenderDistance);
		this.entityShadowMaxDistance = getDouble(tuning, "entityShadowMaxDistance", this.entityShadowMaxDistance);
		this.cullWeatherBehindCamera = getBool(tuning, "cullWeatherBehindCamera", this.cullWeatherBehindCamera);
		this.occlusionMaxRaySteps = getInt(tuning, "occlusionMaxRaySteps", this.occlusionMaxRaySteps);
		this.occlusionCacheFrames = getInt(tuning, "occlusionCacheFrames", this.occlusionCacheFrames);
		this.lightmapEpsilon = getFloat(tuning, "lightmapEpsilon", this.lightmapEpsilon);
		this.debugHudUpdateIntervalMs = getInt(tuning, "debugHudUpdateIntervalMs", this.debugHudUpdateIntervalMs);
		this.showF3Summary = getBool(tuning, "showF3Summary", this.showF3Summary);
		this.maxIdenticalSoundsPerTick = getInt(tuning, "maxIdenticalSoundsPerTick", this.maxIdenticalSoundsPerTick);
		this.chunkLookupCacheSize = getInt(tuning, "chunkLookupCacheSize", this.chunkLookupCacheSize);

		if (!root.has("tuning")
			|| !tuning.has("particlePhysicsMaxDistance")
			|| !tuning.has("signTextMaxRenderDistance")
			|| !tuning.has("entityShadowMaxDistance")
			|| !tuning.has("cullWeatherBehindCamera")
			|| !tuning.has("maxIdenticalSoundsPerTick")
		) {
			migrated = true;
		}
		return migrated;
	}

	public JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", this.schemaVersion);
		root.addProperty("autoCompatibilityMode", this.autoCompatibilityMode);
		root.addProperty("respectCompanionConfigs", this.respectCompanionConfigs);
		root.addProperty("logCompatibilityReport", this.logCompatibilityReport);

		JsonObject modulesObj = new JsonObject();
		for (SiliciumModule module : SiliciumModule.values()) {
			modulesObj.addProperty(module.id(), moduleMode(module).configValue());
		}
		root.add("modules", modulesObj);

		JsonObject tuning = new JsonObject();
		tuning.addProperty("particleMaxRenderDistance", this.particleMaxRenderDistance);
		tuning.addProperty("particlePhysicsMaxDistance", this.particlePhysicsMaxDistance);
		tuning.addProperty("cullParticlesInsideOpaqueBlocks", this.cullParticlesInsideOpaqueBlocks);
		tuning.addProperty("entityCullingMinDistance", this.entityCullingMinDistance);
		tuning.addProperty("blockEntityCullingMinDistance", this.blockEntityCullingMinDistance);
		tuning.addProperty("signTextMaxRenderDistance", this.signTextMaxRenderDistance);
		tuning.addProperty("entityShadowMaxDistance", this.entityShadowMaxDistance);
		tuning.addProperty("cullWeatherBehindCamera", this.cullWeatherBehindCamera);
		tuning.addProperty("occlusionMaxRaySteps", this.occlusionMaxRaySteps);
		tuning.addProperty("occlusionCacheFrames", this.occlusionCacheFrames);
		tuning.addProperty("lightmapEpsilon", this.lightmapEpsilon);
		tuning.addProperty("debugHudUpdateIntervalMs", this.debugHudUpdateIntervalMs);
		tuning.addProperty("showF3Summary", this.showF3Summary);
		tuning.addProperty("maxIdenticalSoundsPerTick", this.maxIdenticalSoundsPerTick);
		tuning.addProperty("chunkLookupCacheSize", this.chunkLookupCacheSize);
		root.add("tuning", tuning);

		return root;
	}

	public void sanitize() {
		this.particleMaxRenderDistance = clamp(this.particleMaxRenderDistance, 16.0, 256.0);
		this.particlePhysicsMaxDistance = clamp(this.particlePhysicsMaxDistance, 8.0, 128.0);
		this.entityCullingMinDistance = clamp(this.entityCullingMinDistance, 4.0, 64.0);
		this.blockEntityCullingMinDistance = clamp(this.blockEntityCullingMinDistance, 4.0, 64.0);
		this.signTextMaxRenderDistance = clamp(this.signTextMaxRenderDistance, 8.0, 128.0);
		this.entityShadowMaxDistance = clamp(this.entityShadowMaxDistance, 4.0, 16.0);
		this.occlusionMaxRaySteps = Math.max(8, Math.min(128, this.occlusionMaxRaySteps));
		this.occlusionCacheFrames = Math.max(1, Math.min(10, this.occlusionCacheFrames));
		this.lightmapEpsilon = (float) clamp(this.lightmapEpsilon, 0.0, 0.01);
		this.debugHudUpdateIntervalMs = Math.max(0, Math.min(500, this.debugHudUpdateIntervalMs));
		this.maxIdenticalSoundsPerTick = Math.max(0, Math.min(32, this.maxIdenticalSoundsPerTick));
		this.chunkLookupCacheSize = Math.max(16, Math.min(1024, this.chunkLookupCacheSize));

		this.particleMaxRenderDistanceSq = this.particleMaxRenderDistance * this.particleMaxRenderDistance;
		double spawnDist = this.particleMaxRenderDistance + 4.0;
		this.particleSpawnMaxDistanceSq = spawnDist * spawnDist;
		this.particlePhysicsMaxDistanceSq = this.particlePhysicsMaxDistance * this.particlePhysicsMaxDistance;
		this.entityCullingMinDistanceSq = this.entityCullingMinDistance * this.entityCullingMinDistance;
		this.blockEntityCullingMinDistanceSq = this.blockEntityCullingMinDistance * this.blockEntityCullingMinDistance;
		this.signTextMaxRenderDistanceSq = this.signTextMaxRenderDistance * this.signTextMaxRenderDistance;
		this.entityShadowMaxDistanceSq = this.entityShadowMaxDistance * this.entityShadowMaxDistance;
	}

	public SiliciumModule.Mode moduleMode(SiliciumModule module) {
		SiliciumModule.Mode mode = this.modules.get(module.id());
		return mode != null ? mode : SiliciumModule.Mode.AUTO;
	}

	public void setModuleMode(SiliciumModule module, SiliciumModule.Mode mode) {
		this.modules.put(module.id(), mode != null ? mode : SiliciumModule.Mode.AUTO);
	}

	public double particleMaxRenderDistanceSq() {
		return this.particleMaxRenderDistanceSq;
	}

	public double particleSpawnMaxDistanceSq() {
		return this.particleSpawnMaxDistanceSq;
	}

	public double particlePhysicsMaxDistanceSq() {
		return this.particlePhysicsMaxDistanceSq;
	}

	public double entityCullingMinDistanceSq() {
		return this.entityCullingMinDistanceSq;
	}

	public double blockEntityCullingMinDistanceSq() {
		return this.blockEntityCullingMinDistanceSq;
	}

	public double signTextMaxRenderDistanceSq() {
		return this.signTextMaxRenderDistanceSq;
	}

	public double entityShadowMaxDistanceSq() {
		return this.entityShadowMaxDistanceSq;
	}

	private static boolean getBool(JsonObject obj, String key, boolean fallback) {
		JsonElement elem = obj.get(key);
		return elem != null && elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isBoolean()
			? elem.getAsBoolean()
			: fallback;
	}

	private static int getInt(JsonObject obj, String key, int fallback) {
		JsonElement elem = obj.get(key);
		return elem != null && elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()
			? elem.getAsInt()
			: fallback;
	}

	private static double getDouble(JsonObject obj, String key, double fallback) {
		JsonElement elem = obj.get(key);
		return elem != null && elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()
			? elem.getAsDouble()
			: fallback;
	}

	private static float getFloat(JsonObject obj, String key, float fallback) {
		JsonElement elem = obj.get(key);
		return elem != null && elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()
			? elem.getAsFloat()
			: fallback;
	}

	private static double clamp(double v, double min, double max) {
		return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
	}
}
