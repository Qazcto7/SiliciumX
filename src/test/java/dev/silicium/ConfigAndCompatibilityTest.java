package dev.silicium;

import dev.silicium.compat.CompanionConfigReader;
import dev.silicium.compat.ModCompatibility;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.module.SiliciumModule;
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
		SiliciumConfig config = new SiliciumConfig();
		ModCompatibility compat = new ModCompatibility(config, modId -> false);

		assertTrue(compat.detectedMods().isEmpty());
		assertEquals(SiliciumModule.values().length, compat.countActive());
		assertEquals(0, compat.countByState(ModCompatibility.State.YIELDED_TO_MOD));
	}

	@Test
	void sodiumAloneCausesZeroConflicts() {
		SiliciumConfig config = new SiliciumConfig();
		ModCompatibility compat = new ModCompatibility(config, "sodium"::equals);

		assertTrue(compat.isModDetected("sodium"));
		assertEquals(SiliciumModule.values().length, compat.countActive());
		assertEquals(0, compat.countByState(ModCompatibility.State.YIELDED_TO_MOD));
	}

	@Test
	void precisionCompatKeepsNonOverlappingModulesActiveAlongsideFullStack() {
		SiliciumConfig config = new SiliciumConfig();
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
		assertFalse(compat.isModuleActive(SiliciumModule.CPU_FAST_SINE_LUT));
		assertEquals("lithium", compat.resolution(SiliciumModule.CPU_FAST_SINE_LUT).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumModule.CPU_PATHFINDING_ALLOC));
		assertFalse(compat.isModuleActive(SiliciumModule.CPU_HOPPER_FAST_SCAN));
		assertFalse(compat.isModuleActive(SiliciumModule.CPU_CHUNK_LOOKUP_CACHE));
		assertFalse(compat.isModuleActive(SiliciumModule.MEMORY_BLOCKSTATE_CACHE_DEDUP));
		assertEquals("ferritecore", compat.resolution(SiliciumModule.MEMORY_BLOCKSTATE_CACHE_DEDUP).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING));
		assertEquals("betterblockentities", compat.resolution(SiliciumModule.GPU_BLOCK_ENTITY_OCCLUSION_CULLING).yieldedToMod());
		assertFalse(compat.isModuleActive(SiliciumModule.GPU_SIGN_AND_SHADOW_CULLING));
		assertEquals("betterblockentities", compat.resolution(SiliciumModule.GPU_SIGN_AND_SHADOW_CULLING).yieldedToMod());

		// Method-level verified non-overlapping & white-space modules stay ACTIVE!
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_FAST_BIOME_VORONOI));
		assertTrue(compat.isModuleActive(SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE));
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_OCCLUSION_SHAPE_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_FACE_OCCLUSION_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_ATTRIBUTE_FAST_CALC));
		assertTrue(compat.isModuleActive(SiliciumModule.MEMORY_NBT_KEY_DEDUP));
		assertTrue(compat.isModuleActive(SiliciumModule.MEMORY_NBT_STRING_DEDUP));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_LIGHTMAP_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_PARTICLE_CULLING));
		assertTrue(compat.isModuleActive(SiliciumModule.CLIENT_PARTICLE_SPAWN_PHYSICS));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_ENTITY_OCCLUSION_CULLING));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_WEATHER_CULLING));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_HUD_DEBUG_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_TEXT_WIDTH_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.GPU_RICH_TEXT_WIDTH_CACHE));
		assertTrue(compat.isModuleActive(SiliciumModule.CLIENT_ANIMATE_TICK_OPT));
		assertTrue(compat.isModuleActive(SiliciumModule.CLIENT_SOUND_ENGINE_OPT));
		assertTrue(compat.isModuleActive(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER));
		assertTrue(compat.isModuleActive(SiliciumModule.DIAGNOSTICS_TELEMETRY));
		assertEquals(19, compat.countActive());

		// Sound engine optimization cleanly yields to Sound Physics Remastered
		ModCompatibility soundCompat = new ModCompatibility(config, "sound_physics_remastered"::equals);
		assertFalse(soundCompat.isModuleActive(SiliciumModule.CLIENT_SOUND_ENGINE_OPT));
		assertEquals("sound_physics_remastered", soundCompat.resolution(SiliciumModule.CLIENT_SOUND_ENGINE_OPT).yieldedToMod());
		assertTrue(soundCompat.isModuleActive(SiliciumModule.GPU_SIGN_AND_SHADOW_CULLING));

		// Worldgen & shader compat yield checks
		ModCompatibility c2meCompat = new ModCompatibility(config, "c2me"::equals);
		assertFalse(c2meCompat.isModuleActive(SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE));
		assertEquals("c2me", c2meCompat.resolution(SiliciumModule.WORLDGEN_FAST_PERLIN_NOISE).yieldedToMod());

		ModCompatibility irisCompat = new ModCompatibility(config, "iris"::equals);
		assertFalse(irisCompat.isModuleActive(SiliciumModule.GPU_WEATHER_CULLING));
		assertEquals("iris", irisCompat.resolution(SiliciumModule.GPU_WEATHER_CULLING).yieldedToMod());
		assertFalse(irisCompat.isModuleActive(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER));
		assertEquals("iris", irisCompat.resolution(SiliciumModule.CLIENT_ASYNC_OCCLUSION_WORKER).yieldedToMod());
	}

	@Test
	void perModuleForceAndOffModesWorkIndependently() {
		SiliciumConfig config = new SiliciumConfig();
		config.setModuleMode(SiliciumModule.GPU_PARTICLE_CULLING, SiliciumModule.Mode.OFF);
		config.setModuleMode(SiliciumModule.CPU_FAST_SINE_LUT, SiliciumModule.Mode.FORCE);

		ModCompatibility compat = new ModCompatibility(config, "lithium"::equals);
		assertEquals(
			ModCompatibility.State.DISABLED_BY_CONFIG,
			compat.resolution(SiliciumModule.GPU_PARTICLE_CULLING).state()
		);
		assertEquals(
			ModCompatibility.State.FORCED_ACTIVE,
			compat.resolution(SiliciumModule.CPU_FAST_SINE_LUT).state()
		);
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_FAST_SINE_LUT));
		// Other lithium-overlapping modules still yield automatically in AUTO mode
		assertEquals(
			ModCompatibility.State.YIELDED_TO_MOD,
			compat.resolution(SiliciumModule.CPU_HOPPER_FAST_SCAN).state()
		);
	}

	@Test
	void lithiumPropertiesDisabledRuleTriggersAutomaticSiliciumTakeover(@TempDir Path tempDir) throws Exception {
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

		SiliciumConfig config = new SiliciumConfig();
		ModCompatibility compat = new ModCompatibility(config, "lithium"::equals, reader);

		// Silicium steps in automatically where Lithium rules were turned off
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_FAST_SINE_LUT));
		assertNotNull(compat.resolution(SiliciumModule.CPU_FAST_SINE_LUT).note());
		assertTrue(compat.isModuleActive(SiliciumModule.CPU_HOPPER_FAST_SCAN));

		// Still yields where Lithium rule remains enabled
		assertFalse(compat.isModuleActive(SiliciumModule.CPU_PATHFINDING_ALLOC));
		assertEquals("lithium", compat.resolution(SiliciumModule.CPU_PATHFINDING_ALLOC).yieldedToMod());
	}

	@Test
	void configMigratesLegacyBooleansParsesModesAndSanitizes(@TempDir Path tempDir) throws Exception {
		Path cfgFile = tempDir.resolve("silicium.json");
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

		SiliciumConfig loaded = SiliciumConfig.load(cfgFile);
		assertEquals(SiliciumModule.Mode.OFF, loaded.moduleMode(SiliciumModule.GPU_PARTICLE_CULLING));
		assertEquals(SiliciumModule.Mode.FORCE, loaded.moduleMode(SiliciumModule.CPU_FAST_SINE_LUT));
		assertEquals(SiliciumModule.Mode.AUTO, loaded.moduleMode(SiliciumModule.CPU_FAST_BIOME_VORONOI));
		assertEquals(SiliciumModule.Mode.AUTO, loaded.moduleMode(SiliciumModule.MEMORY_NBT_STRING_DEDUP));
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
		Path cfgFile = tempDir.resolve("silicium.json");
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

		SiliciumConfig loaded = SiliciumConfig.load(cfgFile);
		// Invalid fields safely fall back to defaults without wiping valid settings.
		assertTrue(loaded.autoCompatibilityMode);
		assertEquals(50, loaded.debugHudUpdateIntervalMs);
		assertEquals(0.002f, loaded.lightmapEpsilon, 1e-6f);
		// Valid fields are preserved cleanly.
		assertEquals(SiliciumModule.Mode.OFF, loaded.moduleMode(SiliciumModule.GPU_PARTICLE_CULLING));
		assertEquals(128.0, loaded.particleMaxRenderDistance);
		assertEquals(128.0 * 128.0, loaded.particleMaxRenderDistanceSq());
	}

	@Test
	void sanitizeClampsNaNAndDetectsRestartRequirements() {
		SiliciumConfig cfg = new SiliciumConfig();
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

		SiliciumConfig boot = new SiliciumConfig();
		SiliciumConfig candidate = boot.copy();
		assertFalse(boot.differsInBootOptions(candidate));
		// Tuning-only change does NOT require restart
		candidate.debugHudUpdateIntervalMs = 100;
		assertFalse(boot.differsInBootOptions(candidate));
		// Module change DOES require restart
		candidate.setModuleMode(SiliciumModule.GPU_PARTICLE_CULLING, SiliciumModule.Mode.OFF);
		assertTrue(boot.differsInBootOptions(candidate));
	}
}
