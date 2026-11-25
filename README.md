# Silicium — Universal Performance Mod for Minecraft (Fabric 26.3)

**Silicium** is a universal, **Vanilla-faithful**, drop-in performance mod for **Minecraft 26.3 (Fabric)** that targets **CPU, GPU, and memory bottlenecks** together while remaining **100% Sodium-friendly** and automatically avoiding conflicts with companion optimization mods.

- **Minecraft:** `26.3`
- **Fabric Loader:** `>= 0.19.5`
- **Java:** `>= 25`
- **Mandatory Dependencies:** None (does not require Fabric API at runtime; works out of the box on Client, Singleplayer, and Dedicated Servers).

---

## Core Design Principles

- **Vanilla-Faithful:** Zero shortcuts that alter redstone contraptions, farms, mob physics, or visual integrity.
- **Sodium-Friendly:** Never touches Sodium's chunk rendering engine; delivers smooth standalone gains and seamlessly complements Sodium (`0 conflicts`).
- **Precision Auto-Compatibility:** Automatically detects installed optimization mods at Mixin-load time (`SiliciumMixinPlugin`) and only yields modules with exact method-level overlaps — keeping all complementary modules active even inside full optimization stacks. Also reads `config/lithium.properties` so if you disable a specific Lithium rule, Silicium automatically steps in.
- **Granular Modular Control:** Every optimization supports `"auto"`, `"force"`, or `"off"` independently in `config/silicium.json`.
- **Data-Driven:** Built-in zero-allocation MSPT / frame-time percentile telemetry (`mean / p50 / p95 / p99 / max`), per-module optimization counters, and an in-JVM microbenchmark (`/silicium bench`).

---

## Module Architecture (26 Modules)

### 1. CPU, Worldgen & Audio Optimizations (Logic, Tick, Noise & Sound)

| Module ID | Target | Description | Auto-Yields To |
| :--- | :--- | :--- | :--- |
| `cpu.fast_sine_lut` | `Mth.sin / cos` | Replaces Vanilla's 256 KB sine table with a 64 KB quarter-wave symmetry LUT (4x smaller) for improved L1/L2 CPU cache residency. | `lithium` (`mixin.math.sine_lut`), `fastmath` |
| `cpu.fast_biome_voronoi` | `BiomeManager` | Evaluates the geometrically nearest Voronoi corner first and prunes unreachable far corners using an exact mathematical lower bound (**100% bit-for-bit identical to Vanilla**). | `c2me`, `noisium` |
| `worldgen.fast_perlin_noise` | `GradientNoise`, `PerlinNoise` | Replaces virtual `permute()` calls, heap `Gradient` record indirection, and nested lerp calls with flat branchless evaluation (**100% bit-for-bit identical worlds**). | `c2me`, `noisium` |
| `cpu.chunk_lookup_cache` | `ServerChunkCache` | Adds a zero-allocation, main-thread 128-slot 2-way associative `FULL` chunk cache on top of Vanilla's 4-entry array (cleared coherently on `clearCache`). | `lithium` (`mixin.world.chunk_access`), `c2me`, `vmp` |
| `cpu.occlusion_shape_cache` | `Block.shouldRenderFace` | Eliminates per-call `Block$ShapePairKey` allocations and linked-map traversal with a ThreadLocal 512-slot flat identity cache. | `moreculling` |
| `cpu.face_occlusion_cache` | `Shapes.faceShapeOccludes` | Caches non-trivial face-occlusion shape tests in a zero-allocation ThreadLocal 512-slot identity cache, avoiding temporary `joinUnoptimized` shape allocations. | None (complementary) |
| `cpu.attribute_fast_calc` | `AttributeInstance.calculateValue` | Fast-paths unmodified entity attributes (~95% of mob attributes), skipping 3 `EnumMap` lookups and 3 empty iterator passes. | None (complementary) |
| `cpu.pathfinding_alloc` | `WalkNodeEvaluator` | Eliminates per-node capturing lambdas, `EnumSet<PathType>`, and `Iterator` allocations in mob A* pathfinding via a 32-bit bitmask preserving exact Vanilla priority and tie-breaking order. | `lithium` (`mixin.ai.pathing`) |
| `cpu.hopper_fast_scan` | `HopperBlockEntity` | Eliminates iterator churn in `inventoryFull()` and short-circuits `suckInItems` slot scanning when the source container above is empty, preserving exact Vanilla cooldowns. | `lithium` (`mixin.block.hopper`) |
| `client.sound_engine_opt` | `SoundEngine.play` | Skips inaudible one-shot sounds strictly beyond their OpenAL attenuation radius before `ChannelAccess.createHandle().join()` stalls the render thread, and caps same-tick identical sound spam (leaving music, UI, streaming, subtitles, and looping sounds untouched). | `sound_physics_remastered` |
| `client.async_occlusion_worker` | `AsyncOcclusionEngine` | Multi-core wait-free worker engine offloading 9-point entity and block-entity DDA occlusion raycasts off the Render Thread (falls back seamlessly to synchronous raycasting if disabled). | `entityculling`, `moreculling`, `iris`, `oculus` |

### 2. Memory Optimizations (RAM & GC Pressure)

| Module ID | Target | Description | Auto-Yields To |
| :--- | :--- | :--- | :--- |
| `memory.blockstate_cache_dedup` | `BlockStateBase.initCache` | Deduplicates immutable `BlockStateBase$Cache` instances and per-face `VoxelShape[6]` occlusion arrays shared across tens of thousands of `BlockState`s. | `ferritecore` |
| `memory.nbt_key_dedup` | `CompoundTag$1.readString` | Canonicalizes repeated NBT compound key strings (`id`, `Name`, `Palette`, `Slot`, etc.) using a lock-free, fixed-size 4096-slot bounded interner (zero leaks, zero `String.intern()` overhead). | None (complementary) |
| `memory.nbt_string_dedup` | `StringTag.valueOf` | Canonicalizes short immutable `StringTag` instances (`<= 64` chars) in a lock-free 4096-slot bounded pool, eliminating duplicate NBT string tags across chunk palettes, entities, items, and packets. | None (complementary) |

### 3. GPU & Render Optimizations (Sodium-Friendly)

| Module ID | Target | Description | Auto-Yields To |
| :--- | :--- | :--- | :--- |
| `gpu.lightmap_cache` | `Lightmap.render` | Skips redundant GPU UBO buffer maps and lightmap render passes when input deltas stay below an 8-bit sub-LSB threshold (`< 1/255`). Works on both OpenGL and Vulkan RenderPearl backends. | `badoptimizations` |
| `gpu.particle_culling` | `QuadParticleGroup` | Sodium-friendly max-distance culling **before** Vanilla's 6-plane Frustum matrix check plus zero-allocation culling for particles enclosed inside full opaque solid blocks (`isSolidRender()`). | `particle_core`, `moreculling` |
| `client.particle_spawn_physics` | `ParticleEngine`, `Particle.move` | Rejects non-priority off-distance particle spawns before heap allocation and skips expensive `VoxelShape` block-collision physics for distant particles beyond `particlePhysicsMaxDistance`. | `particle_core` |
| `gpu.entity_occlusion_culling` | `LevelExtractor` | Conservative 9-point (center + 8 expanded AABB corners) 3D DDA solid-block occlusion culler for entities with short-TTL visibility caching. Players, bosses, vehicles, displays, and glowing/named/leashed entities are never culled. | `entityculling`, `moreculling`, `iris`, `oculus` |
| `gpu.block_entity_occlusion_culling` | `BlockEntityRenderDispatcher` | Conservative 9-point 3D DDA solid-block occlusion culler for block entities (injects after renderer & range checks; skips off-screen renderers and moving pistons, and uses expanded bounds for banners, shulker boxes, and enchanting tables). | `entityculling`, `moreculling`, `betterblockentities`, `iris`, `oculus` |
| `gpu.sign_and_shadow_culling` | `AbstractSignRenderer`, `EntityRenderer` | Eliminates blank, back-facing, and distant sign text passes, caps entity blob shadow extraction distance, and reuses chunk lookups across each entity's shadow footprint. | `moreculling`, `betterblockentities` |
| `gpu.weather_culling` | `WeatherEffectRenderer` | Caches rain/snow column height, 3D biome precipitation, and skylight queries with staggered TTL and skips weather columns strictly behind the camera plane. | `iris`, `oculus` |
| `gpu.hud_debug_cache` | `DebugScreenOverlay` | Throttles F3 debug overlay text rebuilding to 20 Hz (50 ms) while keeping F3 performance charts at full framerate, cutting F3 String/List GC churn by 10x–20x. Optionally displays a single-line `[Silicium]` summary when full F3 is open. | `badoptimizations`, `exordium` |
| `gpu.text_width_cache` | `StringSplitter.stringWidth(String)` | Per-font 512-slot width cache (auto-invalidated on resource reload & font option updates via `FontManager`), eliminating per-call `MutableFloat` and lambda allocations across HUD, chat, F3, tooltips, and menus. | None (complementary) |
| `gpu.rich_text_width_cache` | `StringSplitter.stringWidth(FormattedText)` | Per-font 512-slot immutable `(text/key, Style)` width cache for styled literals and zero-arg translations (buttons, labels, tooltips, chat, scoreboard, nametags), auto-invalidated on font & language reloads. | None (complementary) |
| `client.animate_tick_opt` | `ClientLevel.doAnimateTick` | Eliminates 1,334 redundant `getFluidState` chunk/section lookups per client tick (26,680 lookups/sec) by reusing the `BlockState` already fetched at the exact same position. | `badoptimizations` |

### 4. Telemetry & Diagnostics

| Module ID | Target | Description |
| :--- | :--- | :--- |
| `diagnostics.telemetry` | `MinecraftServer`, `Minecraft`, `Commands` | Zero-allocation rolling MSPT & frame-time percentile tracking, live per-module counters, and the `/silicium` command suite. |

---

## In-Game Commands

| Command | Description |
| :--- | :--- |
| `/silicium` or `/silicium status` | Overview of version, active/forced/yielded/disabled modules, MSPT/TPS, and client frame time. |
| `/silicium modules` | Per-module status list (`[ACTIVE]`, `[FORCE]`, `[YIELD -> <mod>]`, `[OFF]`) plus takeover notes. |
| `/silicium compat` | Detected companion optimization mods (Sodium, Lithium, FerriteCore, ModernFix, ImmediatelyFast, C2ME, EntityCulling, Iris, BBE, etc.) and auto-yield report. |
| `/silicium metrics` | Live MSPT (`mean/min/p50/p95/p99/max`), client frame times, and all active optimization counters. |
| `/silicium metrics reset` | Resets rolling timing windows and counters for clean A/B comparisons. |
| `/silicium bench` | Runs a quick in-JVM microbenchmark comparing Silicium algorithms against Vanilla baselines. |
| `/silicium reload` | Reloads runtime tuning values from `config/silicium.json` without restarting. |

---

## Configuration (`config/silicium.json` & Mod Menu)

Generated automatically on first launch. If **Mod Menu** is installed, Silicium provides a built-in native configuration screen (no Cloth Config or YACL required) with **General**, **Modules**, **Tuning**, and **Telemetry** tabs.

Each entry under `modules` accepts:
- `"auto"` — active unless an exact conflicting companion optimization is loaded and enabled.
- `"force"` — always active regardless of companion mods.
- `"off"` — disabled.

Module mode changes apply on next restart; all `tuning` parameters reload live via **Save & Close** in Mod Menu or `/silicium reload`.

---

## Building & Testing

```bash
# Requires JDK 25+
./gradlew build

# Strict Mixin bytecode audit across all common + client mixins
./gradlew runAuditClient

# Output JAR
# build/libs/silicium-1.0.0+mc26.3.jar
```
