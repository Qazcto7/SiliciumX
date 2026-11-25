package dev.siliciumx;

import dev.siliciumx.compat.CompanionConfigReader;
import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.module.SiliciumXModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndCompatibilityTest {
	@Test
	void standaloneModeActivatesEveryModule() {
		SiliciumXConfig config = new SiliciumXConfig();
		ModCompatibility compat = new ModCompatibility(config, modId -> false);

		assertTrue(compat.detectedMods().isEmpty());
		assertEquals(SiliciumXModule.values().length, compat.countActive());
		assertEquals(0, compat.countByState(ModCompatibility.State.YIELDED_TO_MOD));
	}

	@Test
	void sodiumAloneCausesZeroConflicts() {
		SiliciumXConfig config = new SiliciumXConfig();
		ModCompatibility compat = new ModCompatibility(config, "sodium"::equals);

		assertTrue(compat.isModDetected("sodium"));
		assertEquals(SiliciumXModule.values().length, compat.countActive());
		assertEquals(0, compat.countByState(ModCompatibility.State.YIELDED_TO_MOD));
	}

	@Test
	void precisionCompatKeepsNonOverlappingModulesActiveAlongsideFullStack() {
		SiliciumXConfig config = new SiliciumXConfig();
		Set<String> loaded = Set.of(
			"sodium",
			"lithium",
			"ferritecore",
			"modernfix",
			"immediatelyfast",
			"betterblockentities",
			"scalablelux"
		);
		ModCompatibility compat = new ModCompatibility(config, loaded::contains);

		// Exact-overlap modules yield cleanly
		assertFalse(compat.isModuleActive(SiliciumXModule.CPU_FAST_SINE_LUT));
		assertEquals("lithium", compat.resolution(SiliciumXModule.CPU_FAST_SINE_LUT).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumXModule.CPU_PATHFINDING_ALLOC));
		assertFalse(compat.isModuleActive(SiliciumXModule.CPU_HOPPER_FAST_SCAN));
		assertFalse(compat.isModuleActive(SiliciumXModule.CPU_CHUNK_LOOKUP_CACHE));
		assertFalse(compat.isModuleActive(SiliciumXModule.MEMORY_BLOCKSTATE_CACHE_DEDUP));
		assertEquals("ferritecore", compat.resolution(SiliciumXModule.MEMORY_BLOCKSTATE_CACHE_DEDUP).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumXModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING));
		assertEquals("betterblockentities", compat.resolution(SiliciumXModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumXModule.GPU_SIGN_AND_SHADOW_CULLING));
		assertEquals("betterblockentities", compat.resolution(SiliciumXModule.GPU_SIGN_AND_SHADOW_CULLING).yieldedToMod());

		// Method-level verified non-overlapping & white-space modules stay ACTIVE!
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_FAST_BIOME_VORONOI));
		assertTrue(compat.isModuleActive(SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE));
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_OCCLUSION_SHAPE_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_FACE_OCCLUSION_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_ATTRIBUTE_FAST_CALC));
		assertTrue(compat.isModuleActive(SiliciumXModule.MEMORY_NBT_KEY_DEDUP));
		assertTrue(compat.isModuleActive(SiliciumXModule.MEMORY_NBT_STRING_DEDUP));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_LIGHTMAP_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_PARTICLE_CULLING));
		assertTrue(compat.isModuleActive(SiliciumXModule.CLIENT_PARTICLE_SPAWN_PHYSICS));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_ENTITY_OCCLUSION_CULLING));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_WEATHER_CULLING));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_HUD_DEBUG_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_TEXT_WIDTH_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.GPU_RICH_TEXT_WIDTH_CACHE));
		assertTrue(compat.isModuleActive(SiliciumXModule.CLIENT_ANIMATE_TICK_OPT));
		assertTrue(compat.isModuleActive(SiliciumXModule.CLIENT_SOUND_ENGINE_OPT));
		assertTrue(compat.isModuleActive(SiliciumXModule.CLIENT_ASYNC_OCCLUSION_WORKER));
		assertTrue(compat.isModuleActive(SiliciumXModule.DIAGNOSTICS_TELEMETRY));
		assertEquals(19, compat.countActive());

		// Sound engine optimization cleanly yields to Sound Physics Remastered
		ModCompatibility soundCompat = new ModCompatibility(config, "sound_physics_remastered"::equals);
		assertFalse(soundCompat.isModuleActive(SiliciumXModule.CLIENT_SOUND_ENGINE_OPT));
		assertEquals("sound_physics_remastered", soundCompat.resolution(SiliciumXModule.CLIENT_SOUND_ENGINE_OPT).yieldedToMod());
		assertTrue(soundCompat.isModuleActive(SiliciumXModule.GPU_SIGN_AND_SHADOW_CULLING));

		// Worldgen & shader compat yield checks
		ModCompatibility c2meCompat = new ModCompatibility(config, "c2me"::equals);
		assertFalse(c2meCompat.isModuleActive(SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE));
		assertEquals("c2me", c2meCompat.resolution(SiliciumXModule.WORLDGEN_FAST_PERLIN_NOISE).yieldedToMod());

		ModCompatibility irisCompat = new ModCompatibility(config, "iris"::equals);
		assertFalse(irisCompat.isModuleActive(SiliciumXModule.GPU_WEATHER_CULLING));
		assertEquals("iris", irisCompat.resolution(SiliciumXModule.GPU_WEATHER_CULLING).yieldedToMod());
		assertFalse(irisCompat.isModuleActive(SiliciumXModule.CLIENT_ASYNC_OCCLUSION_WORKER));
		assertEquals("iris", irisCompat.resolution(SiliciumXModule.CLIENT_ASYNC_OCCLUSION_WORKER).yieldedToMod());
	}

	@Test
	void perModuleForceAndOffModesWorkIndependently() {
		SiliciumXConfig config = new SiliciumXConfig();
		config.setModuleMode(SiliciumXModule.GPU_PARTICLE_CULLING, SiliciumXModule.Mode.OFF);
		config.setModuleMode(SiliciumXModule.CPU_FAST_SINE_LUT, SiliciumXModule.Mode.FORCE);

		ModCompatibility compat = new ModCompatibility(config, "lithium"::equals);
		assertEquals(
			ModCompatibility.State.DISABLED_BY_CONFIG,
			compat.resolution(SiliciumXModule.GPU_PARTICLE_CULLING).state()
		);
		assertEquals(
			ModCompatibility.State.FORCED_ACTIVE,
			compat.resolution(SiliciumXModule.CPU_FAST_SINE_LUT).state()
		);
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_FAST_SINE_LUT));
		// Other lithium-overlapping modules still yield automatically in AUTO mode
		assertEquals(
			ModCompatibility.State.YIELDED_TO_MOD,
			compat.resolution(SiliciumXModule.CPU_HOPPER_FAST_SCAN).state()
		);
	}

	@Test
	void lithiumPropertiesDisabledRuleTriggersAutomaticSiliciumXTakeover(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("lithium.properties"), """
			# Disable sine LUT and entire block category in Lithium
			mixin.math.sine_lut=false
			mixin.block=false
			mixin.ai.pathing=true
			""");

		CompanionConfigReader reader = CompanionConfigReader.fromConfigDir(tempDir);
		assertTrue(reader.isLithiumRuleDisabled("mixin.math.sine_lut"));
		assertTrue(reader.isLithiumRuleDisabled("mixin.block.hopper"));
		assertFalse(reader.isLithiumRuleDisabled("mixin.ai.pathing"));

		SiliciumXConfig config = new SiliciumXConfig();
		ModCompatibility compat = new ModCompatibility(config, "lithium"::equals, reader);

		// SiliciumX steps in automatically where Lithium rules were turned off
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_FAST_SINE_LUT));
		assertNotNull(compat.resolution(SiliciumXModule.CPU_FAST_SINE_LUT).note());
		assertTrue(compat.isModuleActive(SiliciumXModule.CPU_HOPPER_FAST_SCAN));

		// Still yields where Lithium rule remains enabled
		assertFalse(compat.isModuleActive(SiliciumXModule.CPU_PATHFINDING_ALLOC));
		assertEquals("lithium", compat.resolution(SiliciumXModule.CPU_PATHFINDING_ALLOC).yieldedToMod());
	}

	@Test
	void configMigratesLegacyBooleansParsesModesAndSanitizes(@TempDir Path tempDir) throws Exception {
		Path cfgFile = tempDir.resolve("siliciumx.json");
		Files.writeString(cfgFile, """
			{
			  "schemaVersion": 1,
			  "autoCompatibilityMode": true,
			  "modules": {
			    "gpu.particle_culling": false,
			    "cpu.fast_sine_lut": "force",
			    "cpu.fast_biome_voronoi": true
			  },
			  "tuning": {
			    "particleMaxRenderDistance": 9999.0,
			    "debugHudUpdateIntervalMs": -50
			  }
			}
			""");

		SiliciumXConfig loaded = SiliciumXConfig.load(cfgFile);
		assertEquals(SiliciumXModule.Mode.OFF, loaded.moduleMode(SiliciumXModule.GPU_PARTICLE_CULLING));
		assertEquals(SiliciumXModule.Mode.FORCE, loaded.moduleMode(SiliciumXModule.CPU_FAST_SINE_LUT));
		assertEquals(SiliciumXModule.Mode.AUTO, loaded.moduleMode(SiliciumXModule.CPU_FAST_BIOME_VORONOI));
		assertEquals(SiliciumXModule.Mode.AUTO, loaded.moduleMode(SiliciumXModule.MEMORY_NBT_STRING_DEDUP));
		assertEquals(256.0, loaded.particleMaxRenderDistance);
		assertEquals(0, loaded.debugHudUpdateIntervalMs);

		String saved = Files.readString(cfgFile);
		assertTrue(saved.contains("\"schemaVersion\": 2"));
		assertTrue(saved.contains("\"cpu.fast_sine_lut\": \"force\""));
		assertTrue(saved.contains("\"gpu.particle_culling\": \"off\""));
		assertTrue(saved.contains("\"gpu.text_width_cache\": \"auto\""));
	}

	@Test
	void malformedSingleFieldPreservesOtherValidSettings(@TempDir Path tempDir) throws Exception {
		Path cfgFile = tempDir.resolve("siliciumx.json");
		Files.writeString(cfgFile, """
			{
			  "schemaVersion": 2,
			  "autoCompatibilityMode": "not-a-boolean",
			  "modules": {
			    "gpu.particle_culling": "off"
			  },
			  "tuning": {
			    "debugHudUpdateIntervalMs": "bad-int",
			    "particleMaxRenderDistance": 128.0,
			    "lightmapEpsilon": "not-a-float"
			  }
			}
			""");

		SiliciumXConfig loaded = SiliciumXConfig.load(cfgFile);
		// Invalid fields safely fall back to defaults without wiping valid settings.
		assertTrue(loaded.autoCompatibilityMode);
		assertEquals(50, loaded.debugHudUpdateIntervalMs);
		assertEquals(0.002f, loaded.lightmapEpsilon, 1e-6f);
		// Valid fields are preserved cleanly.
		assertEquals(SiliciumXModule.Mode.OFF, loaded.moduleMode(SiliciumXModule.GPU_PARTICLE_CULLING));
		assertEquals(128.0, loaded.particleMaxRenderDistance);
		assertEquals(128.0 * 128.0, loaded.particleMaxRenderDistanceSq());
	}

	@Test
	void sanitizeClampsNaNAndDetectsRestartRequirements() {
		SiliciumXConfig cfg = new SiliciumXConfig();
		cfg.particleMaxRenderDistance = Double.NaN;
		cfg.particlePhysicsMaxDistance = 999.0;
		cfg.signTextMaxRenderDistance = 1.0;
		cfg.entityShadowMaxDistance = 50.0;
		cfg.maxIdenticalSoundsPerTick = -5;
		cfg.lightmapEpsilon = Float.NaN;
		cfg.sanitize();
		assertEquals(16.0, cfg.particleMaxRenderDistance);
		assertEquals(20.0 * 20.0, cfg.particleSpawnMaxDistanceSq());
		assertEquals(128.0, cfg.particlePhysicsMaxDistance);
		assertEquals(8.0, cfg.signTextMaxRenderDistance);
		assertEquals(16.0, cfg.entityShadowMaxDistance);
		assertEquals(0, cfg.maxIdenticalSoundsPerTick);
		assertEquals(0.0f, cfg.lightmapEpsilon);

		SiliciumXConfig boot = new SiliciumXConfig();
		SiliciumXConfig candidate = boot.copy();
		assertFalse(boot.differsInBootOptions(candidate));
		// Tuning-only change does NOT require restart
		candidate.debugHudUpdateIntervalMs = 100;
		assertFalse(boot.differsInBootOptions(candidate));
		// Module change DOES require restart
		candidate.setModuleMode(SiliciumXModule.GPU_PARTICLE_CULLING, SiliciumXModule.Mode.OFF);
		assertTrue(boot.differsInBootOptions(candidate));
	}
}
