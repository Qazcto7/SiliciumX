package dev.siliciumx.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.siliciumx.SiliciumXConstants;
import dev.siliciumx.module.SiliciumXModule;
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
 * Modular JSON configuration for SiliciumX ({@code config/siliciumx.json}).
 *
 * <p>Each module accepts {@code "auto"} (enabled unless an exact conflicting companion optimization
 * is active), {@code "force"} (always active), or {@code "off"} (disabled). Legacy boolean entries
 * ({@code true}/{@code false}) are automatically migrated.</p>
 */
public final class SiliciumXConfig {
	public static final int CURRENT_SCHEMA_VERSION = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile SiliciumXConfig INSTANCE;
	private static volatile SiliciumXConfig BOOT_SNAPSHOT;

	public int schemaVersion = CURRENT_SCHEMA_VERSION;
	public volatile boolean autoCompatibilityMode = true;
	public volatile boolean respectCompanionConfigs = true;
	public volatile boolean logCompatibilityReport = true;

	// Per-module mode: "auto" | "force" | "off" (restart required to change Mixin application)
	public Map<String, SiliciumXModule.Mode> modules = createDefaultModulesMap();

	// Runtime-tunable GPU / Render parameters (volatile for safe cross-thread reload)
	public volatile double particleMaxRenderDistance = 64.0;
	public volatile double particlePhysicsMaxDistance = 24.0;
	public volatile boolean cullParticlesInsideOpaqueBlocks = true;
	public volatile double entityCullingMinDistance = 10.0;
	public volatile double blockEntityCullingMinDistance = 8.0;
	public volatile double signTextMaxRenderDistance = 32.0;
	public volatile double entityShadowMaxDistance = 14.0;
	public volatile double nameTagMaxDistance = 32.0;
	public volatile double cosmeticLayerMaxDistance = 40.0;
	public volatile double itemGlintMaxDistance = 24.0;
	public volatile double itemFrameItemMaxDistance = 40.0;
	public volatile boolean simplifyDistantItemStacks = true;
	public volatile boolean cullWeatherBehindCamera = true;
	public volatile int occlusionMaxRaySteps = 48;
	public volatile int occlusionCacheFrames = 3;
	public volatile float lightmapEpsilon = 0.002f;
	public volatile int debugHudUpdateIntervalMs = 50;
	public volatile int hudCacheUpdateIntervalMs = 50;
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
	private volatile double nameTagMaxDistanceSq = 32.0 * 32.0;
	private volatile double cosmeticLayerMaxDistanceSq = 40.0 * 40.0;
	private volatile double itemGlintMaxDistanceSq = 24.0 * 24.0;
	private volatile double itemFrameItemMaxDistanceSq = 40.0 * 40.0;

	public static Map<String, SiliciumXModule.Mode> createDefaultModulesMap() {
		Map<String, SiliciumXModule.Mode> map = new LinkedHashMap<>();
		for (SiliciumXModule module : SiliciumXModule.values()) {
			map.put(module.id(), SiliciumXModule.Mode.AUTO);
		}
		return map;
	}

	public static SiliciumXConfig get() {
		SiliciumXConfig current = INSTANCE;
		if (current == null) {
			synchronized (SiliciumXConfig.class) {
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
	public static boolean isRestartRequired(SiliciumXConfig candidate) {
		SiliciumXConfig boot = BOOT_SNAPSHOT;
		if (boot == null) {
			boot = get();
		}
		return boot.differsInBootOptions(candidate);
	}

	public boolean differsInBootOptions(SiliciumXConfig other) {
		return this.autoCompatibilityMode != other.autoCompatibilityMode
			|| this.respectCompanionConfigs != other.respectCompanionConfigs
			|| this.chunkLookupCacheSize != other.chunkLookupCacheSize
			|| !this.modules.equals(other.modules);
	}

	public static Path defaultConfigPath() {
		try {
			Path configDir = FabricLoader.getInstance().getConfigDir();
			return configDir != null ? configDir.resolve("siliciumx.json") : null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	public static SiliciumXConfig loadFromDefaultPath() {
		Path path = defaultConfigPath();
		if (path == null) {
			SiliciumXConfig fallback = new SiliciumXConfig();
			fallback.sanitize();
			return fallback;
		}
		return load(path);
	}

	public static SiliciumXConfig load(Path path) {
		SiliciumXConfig config = new SiliciumXConfig();
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
				SiliciumXConstants.LOGGER.warn(
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
	public static synchronized SiliciumXConfig reloadRuntimeSettings() {
		SiliciumXConfig current = get();
		Path path = defaultConfigPath();
		if (path == null) {
			return current;
		}
		if (!Files.exists(path)) {
			save(current, path);
			return current;
		}
		SiliciumXConfig fresh = load(path);
		current.particleMaxRenderDistance = fresh.particleMaxRenderDistance;
		current.particlePhysicsMaxDistance = fresh.particlePhysicsMaxDistance;
		current.cullParticlesInsideOpaqueBlocks = fresh.cullParticlesInsideOpaqueBlocks;
		current.entityCullingMinDistance = fresh.entityCullingMinDistance;
		current.blockEntityCullingMinDistance = fresh.blockEntityCullingMinDistance;
		current.signTextMaxRenderDistance = fresh.signTextMaxRenderDistance;
		current.entityShadowMaxDistance = fresh.entityShadowMaxDistance;
		current.nameTagMaxDistance = fresh.nameTagMaxDistance;
		current.cosmeticLayerMaxDistance = fresh.cosmeticLayerMaxDistance;
		current.itemGlintMaxDistance = fresh.itemGlintMaxDistance;
		current.itemFrameItemMaxDistance = fresh.itemFrameItemMaxDistance;
		current.simplifyDistantItemStacks = fresh.simplifyDistantItemStacks;
		current.cullWeatherBehindCamera = fresh.cullWeatherBehindCamera;
		current.occlusionMaxRaySteps = fresh.occlusionMaxRaySteps;
		current.occlusionCacheFrames = fresh.occlusionCacheFrames;
		current.lightmapEpsilon = fresh.lightmapEpsilon;
		current.debugHudUpdateIntervalMs = fresh.debugHudUpdateIntervalMs;
		current.hudCacheUpdateIntervalMs = fresh.hudCacheUpdateIntervalMs;
		current.showF3Summary = fresh.showF3Summary;
		current.maxIdenticalSoundsPerTick = fresh.maxIdenticalSoundsPerTick;
		current.sanitize();
		return current;
	}

	/**
	 * Creates a deep editable copy of this configuration for UI screens.
	 */
	public SiliciumXConfig copy() {
		SiliciumXConfig copy = new SiliciumXConfig();
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
		copy.nameTagMaxDistance = this.nameTagMaxDistance;
		copy.cosmeticLayerMaxDistance = this.cosmeticLayerMaxDistance;
		copy.itemGlintMaxDistance = this.itemGlintMaxDistance;
		copy.itemFrameItemMaxDistance = this.itemFrameItemMaxDistance;
		copy.simplifyDistantItemStacks = this.simplifyDistantItemStacks;
		copy.cullWeatherBehindCamera = this.cullWeatherBehindCamera;
		copy.occlusionMaxRaySteps = this.occlusionMaxRaySteps;
		copy.occlusionCacheFrames = this.occlusionCacheFrames;
		copy.lightmapEpsilon = this.lightmapEpsilon;
		copy.debugHudUpdateIntervalMs = this.debugHudUpdateIntervalMs;
		copy.hudCacheUpdateIntervalMs = this.hudCacheUpdateIntervalMs;
		copy.showF3Summary = this.showF3Summary;
		copy.maxIdenticalSoundsPerTick = this.maxIdenticalSoundsPerTick;
		copy.chunkLookupCacheSize = this.chunkLookupCacheSize;
		copy.sanitize();
		return copy;
	}

	/**
	 * Applies an edited draft into the live singleton instance and persists it to {@code config/siliciumx.json}.
	 */
	public static synchronized void applyAndSave(SiliciumXConfig draft) {
		draft.sanitize();
		SiliciumXConfig live = get();

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
		live.nameTagMaxDistance = draft.nameTagMaxDistance;
		live.cosmeticLayerMaxDistance = draft.cosmeticLayerMaxDistance;
		live.itemGlintMaxDistance = draft.itemGlintMaxDistance;
		live.itemFrameItemMaxDistance = draft.itemFrameItemMaxDistance;
		live.simplifyDistantItemStacks = draft.simplifyDistantItemStacks;
		live.cullWeatherBehindCamera = draft.cullWeatherBehindCamera;
		live.occlusionMaxRaySteps = draft.occlusionMaxRaySteps;
		live.occlusionCacheFrames = draft.occlusionCacheFrames;
		live.lightmapEpsilon = draft.lightmapEpsilon;
		live.debugHudUpdateIntervalMs = draft.debugHudUpdateIntervalMs;
		live.hudCacheUpdateIntervalMs = draft.hudCacheUpdateIntervalMs;
		live.showF3Summary = draft.showF3Summary;
		live.maxIdenticalSoundsPerTick = draft.maxIdenticalSoundsPerTick;
		live.chunkLookupCacheSize = draft.chunkLookupCacheSize;
		live.sanitize();

		Path path = defaultConfigPath();
		if (path != null) {
			save(live, path);
		}
	}

	public static void save(SiliciumXConfig config, Path path) {
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
				SiliciumXConstants.LOGGER.warn("Could not write SiliciumX config {}: {}", path, e.getMessage());
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
			for (SiliciumXModule module : SiliciumXModule.values()) {
				JsonElement elem = modulesObj.get(module.id());
				if (elem != null && elem.isJsonPrimitive()) {
					JsonPrimitive prim = elem.getAsJsonPrimitive();
					if (prim.isBoolean()) {
						this.modules.put(module.id(), prim.getAsBoolean() ? SiliciumXModule.Mode.AUTO : SiliciumXModule.Mode.OFF);
						migrated = true;
					} else if (prim.isString()) {
						this.modules.put(module.id(), SiliciumXModule.Mode.parse(prim.getAsString(), SiliciumXModule.Mode.AUTO));
					} else {
						this.modules.put(module.id(), SiliciumXModule.Mode.AUTO);
						migrated = true;
					}
				} else {
					this.modules.put(module.id(), SiliciumXModule.Mode.AUTO);
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
		this.nameTagMaxDistance = getDouble(tuning, "nameTagMaxDistance", this.nameTagMaxDistance);
		this.cosmeticLayerMaxDistance = getDouble(tuning, "cosmeticLayerMaxDistance", this.cosmeticLayerMaxDistance);
		this.itemGlintMaxDistance = getDouble(tuning, "itemGlintMaxDistance", this.itemGlintMaxDistance);
		this.itemFrameItemMaxDistance = getDouble(tuning, "itemFrameItemMaxDistance", this.itemFrameItemMaxDistance);
		this.simplifyDistantItemStacks = getBool(tuning, "simplifyDistantItemStacks", this.simplifyDistantItemStacks);
		this.cullWeatherBehindCamera = getBool(tuning, "cullWeatherBehindCamera", this.cullWeatherBehindCamera);
		this.occlusionMaxRaySteps = getInt(tuning, "occlusionMaxRaySteps", this.occlusionMaxRaySteps);
		this.occlusionCacheFrames = getInt(tuning, "occlusionCacheFrames", this.occlusionCacheFrames);
		this.lightmapEpsilon = getFloat(tuning, "lightmapEpsilon", this.lightmapEpsilon);
		this.debugHudUpdateIntervalMs = getInt(tuning, "debugHudUpdateIntervalMs", this.debugHudUpdateIntervalMs);
		this.hudCacheUpdateIntervalMs = getInt(tuning, "hudCacheUpdateIntervalMs", this.hudCacheUpdateIntervalMs);
		this.showF3Summary = getBool(tuning, "showF3Summary", this.showF3Summary);
		this.maxIdenticalSoundsPerTick = getInt(tuning, "maxIdenticalSoundsPerTick", this.maxIdenticalSoundsPerTick);
		this.chunkLookupCacheSize = getInt(tuning, "chunkLookupCacheSize", this.chunkLookupCacheSize);

		if (!root.has("tuning")
			|| !tuning.has("particlePhysicsMaxDistance")
			|| !tuning.has("signTextMaxRenderDistance")
			|| !tuning.has("entityShadowMaxDistance")
			|| !tuning.has("nameTagMaxDistance")
			|| !tuning.has("cosmeticLayerMaxDistance")
			|| !tuning.has("itemGlintMaxDistance")
			|| !tuning.has("itemFrameItemMaxDistance")
			|| !tuning.has("simplifyDistantItemStacks")
			|| !tuning.has("hudCacheUpdateIntervalMs")
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
		for (SiliciumXModule module : SiliciumXModule.values()) {
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
		tuning.addProperty("nameTagMaxDistance", this.nameTagMaxDistance);
		tuning.addProperty("cosmeticLayerMaxDistance", this.cosmeticLayerMaxDistance);
		tuning.addProperty("itemGlintMaxDistance", this.itemGlintMaxDistance);
		tuning.addProperty("itemFrameItemMaxDistance", this.itemFrameItemMaxDistance);
		tuning.addProperty("simplifyDistantItemStacks", this.simplifyDistantItemStacks);
		tuning.addProperty("cullWeatherBehindCamera", this.cullWeatherBehindCamera);
		tuning.addProperty("occlusionMaxRaySteps", this.occlusionMaxRaySteps);
		tuning.addProperty("occlusionCacheFrames", this.occlusionCacheFrames);
		tuning.addProperty("lightmapEpsilon", this.lightmapEpsilon);
		tuning.addProperty("debugHudUpdateIntervalMs", this.debugHudUpdateIntervalMs);
		tuning.addProperty("hudCacheUpdateIntervalMs", this.hudCacheUpdateIntervalMs);
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
		this.nameTagMaxDistance = clamp(this.nameTagMaxDistance, 8.0, 64.0);
		this.cosmeticLayerMaxDistance = clamp(this.cosmeticLayerMaxDistance, 12.0, 128.0);
		this.itemGlintMaxDistance = clamp(this.itemGlintMaxDistance, 6.0, 128.0);
		this.itemFrameItemMaxDistance = clamp(this.itemFrameItemMaxDistance, 12.0, 128.0);
		this.occlusionMaxRaySteps = Math.max(8, Math.min(128, this.occlusionMaxRaySteps));
		this.occlusionCacheFrames = Math.max(1, Math.min(10, this.occlusionCacheFrames));
		this.lightmapEpsilon = (float) clamp(this.lightmapEpsilon, 0.0, 0.01);
		this.debugHudUpdateIntervalMs = Math.max(0, Math.min(500, this.debugHudUpdateIntervalMs));
		this.hudCacheUpdateIntervalMs = Math.max(0, Math.min(500, this.hudCacheUpdateIntervalMs));
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
		this.nameTagMaxDistanceSq = this.nameTagMaxDistance * this.nameTagMaxDistance;
		this.cosmeticLayerMaxDistanceSq = this.cosmeticLayerMaxDistance * this.cosmeticLayerMaxDistance;
		this.itemGlintMaxDistanceSq = this.itemGlintMaxDistance * this.itemGlintMaxDistance;
		this.itemFrameItemMaxDistanceSq = this.itemFrameItemMaxDistance * this.itemFrameItemMaxDistance;
	}

	public SiliciumXModule.Mode moduleMode(SiliciumXModule module) {
		SiliciumXModule.Mode mode = this.modules.get(module.id());
		return mode != null ? mode : SiliciumXModule.Mode.AUTO;
	}

	public void setModuleMode(SiliciumXModule module, SiliciumXModule.Mode mode) {
		this.modules.put(module.id(), mode != null ? mode : SiliciumXModule.Mode.AUTO);
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

	public double nameTagMaxDistanceSq() {
		return this.nameTagMaxDistanceSq;
	}

	public double cosmeticLayerMaxDistanceSq() {
		return this.cosmeticLayerMaxDistanceSq;
	}

	public double itemGlintMaxDistanceSq() {
		return this.itemGlintMaxDistanceSq;
	}

	public double itemFrameItemMaxDistanceSq() {
		return this.itemFrameItemMaxDistanceSq;
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
