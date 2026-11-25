package dev.siliciumx.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.siliciumx.SiliciumXConstants;
import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.module.SiliciumXModule;
import dev.siliciumx.util.RollingTimingWindow;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Native Brigadier {@code /siliciumx} command providing live visibility into active modules,
 * companion optimization mod compatibility, rolling MSPT/FPS percentiles, per-module counters,
 * runtime config reload, and an in-JVM microbenchmark.
 *
 * <p>Registered on the server dispatcher ({@code CommandsMixin}) for singleplayer and dedicated
 * Fabric servers, and transparently handled client-side ({@code ClientCommandMixin}) when
 * connected to remote multiplayer servers that do not have SiliciumX installed.</p>
 */
public final class SiliciumXCommand {
	private SiliciumXCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("siliciumx")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> executeStatus(serverOut(ctx.getSource()), false))
				.then(Commands.literal("status")
					.executes(ctx -> executeStatus(serverOut(ctx.getSource()), false)))
				.then(Commands.literal("modules")
					.executes(ctx -> executeModules(serverOut(ctx.getSource()))))
				.then(Commands.literal("compat")
					.executes(ctx -> executeCompat(serverOut(ctx.getSource()))))
				.then(Commands.literal("metrics")
					.executes(ctx -> executeMetrics(serverOut(ctx.getSource()), false))
					.then(Commands.literal("reset")
						.executes(ctx -> executeMetricsReset(serverOut(ctx.getSource())))))
				.then(Commands.literal("bench")
					.executes(ctx -> executeBench(serverOut(ctx.getSource()))))
				.then(Commands.literal("reload")
					.executes(ctx -> executeReload(serverOut(ctx.getSource()))))
		);
	}

	/**
	 * Registers the {@code /siliciumx} syntax tree into the client's Brigadier suggestion dispatcher
	 * so multiplayer players get full syntax highlighting and Tab completion even on Vanilla/Paper
	 * servers without SiliciumX installed.
	 */
	public static <S> void registerClientSuggestions(CommandDispatcher<S> dispatcher) {
		dispatcher.register(
			LiteralArgumentBuilder.<S>literal("siliciumx")
				.executes(ctx -> 1)
				.then(LiteralArgumentBuilder.<S>literal("status").executes(ctx -> 1))
				.then(LiteralArgumentBuilder.<S>literal("modules").executes(ctx -> 1))
				.then(LiteralArgumentBuilder.<S>literal("compat").executes(ctx -> 1))
				.then(LiteralArgumentBuilder.<S>literal("metrics")
					.executes(ctx -> 1)
					.then(LiteralArgumentBuilder.<S>literal("reset").executes(ctx -> 1)))
				.then(LiteralArgumentBuilder.<S>literal("bench").executes(ctx -> 1))
				.then(LiteralArgumentBuilder.<S>literal("reload").executes(ctx -> 1))
		);
	}

	/**
	 * Intercepts and locally executes a {@code /siliciumx} command on the client when connected to a
	 * remote server that does not provide the command.
	 *
	 * @param rawCommand command string without leading slash (as passed to {@code ClientPacketListener.sendCommand})
	 * @param out        client chat output consumer
	 * @return {@code true} if the command was handled locally and must NOT be sent to the server
	 */
	public static boolean tryExecuteClientCommand(String rawCommand, boolean remoteServer, Consumer<Component> out) {
		if (rawCommand == null) {
			return false;
		}
		String trimmed = rawCommand.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1).trim();
		}
		if (!trimmed.equalsIgnoreCase("siliciumx") && !trimmed.toLowerCase(Locale.ROOT).startsWith("siliciumx ")) {
			return false;
		}

		String[] parts = trimmed.split("\\s+");
		if (parts.length == 1) {
			executeStatus(out, remoteServer);
			return true;
		}

		String sub = parts[1].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "status" -> executeStatus(out, remoteServer);
			case "modules" -> executeModules(out);
			case "compat" -> executeCompat(out);
			case "metrics" -> {
				if (parts.length >= 3 && "reset".equalsIgnoreCase(parts[2])) {
					executeMetricsReset(out);
				} else {
					executeMetrics(out, remoteServer);
				}
			}
			case "bench" -> executeBench(out);
			case "reload" -> executeReload(out);
			default -> out.accept(Component.literal(
				"[SiliciumX] Unknown subcommand '" + parts[1] + "'. Usage: /siliciumx <status|modules|compat|metrics [reset]|bench|reload>"
			).withStyle(ChatFormatting.RED));
		}
		return true;
	}

	private static Consumer<Component> serverOut(CommandSourceStack source) {
		return msg -> source.sendSuccess(() -> msg, false);
	}

	private static String getModVersion() {
		return FabricLoader.getInstance()
			.getModContainer(SiliciumXConstants.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");
	}

	private static int executeStatus(Consumer<Component> out, boolean remoteServer) {
		ModCompatibility compat = ModCompatibility.get();
		int active = compat.countActive();
		int forced = compat.countByState(ModCompatibility.State.FORCED_ACTIVE);
		int yielded = compat.countByState(ModCompatibility.State.YIELDED_TO_MOD);
		int disabled = compat.countByState(ModCompatibility.State.DISABLED_BY_CONFIG);
		RollingTimingWindow.Snapshot tick = SiliciumXMetrics.serverTickSnapshot();

		out.accept(header("SiliciumX " + getModVersion() + (remoteServer ? " — Client Status" : " — Status")));
		String moduleSummary = forced > 0
			? active + " active (" + forced + " forced), " + yielded + " auto-yielded, " + disabled + " disabled"
			: active + " active, " + yielded + " auto-yielded, " + disabled + " disabled";
		out.accept(line("Modules: ", moduleSummary, ChatFormatting.GREEN));
		out.accept(line(
			"Auto-Compat: ",
			compat.isAutoCompatEnabled() ? "ON (" + compat.detectedMods().size() + " ecosystem mods detected)" : "OFF",
			compat.isAutoCompatEnabled() ? ChatFormatting.AQUA : ChatFormatting.YELLOW
		));

		if (remoteServer) {
			out.accept(line("Server MSPT: ", "remote multiplayer server (client optimizations active)", ChatFormatting.GRAY));
		} else if (tick.samples() > 0) {
			out.accept(line(
				"Server MSPT: ",
				String.format(
					Locale.ROOT,
					"mean=%.2fms | p50=%.2fms | p95=%.2fms | p99=%.2fms | TPS=%.1f (%d ticks)",
					tick.meanMs(), tick.p50Ms(), tick.p95Ms(), tick.p99Ms(), tick.estimatedTps(), tick.samples()
				),
				tick.meanMs() <= 35.0 ? ChatFormatting.GREEN : (tick.meanMs() <= 50.0 ? ChatFormatting.YELLOW : ChatFormatting.RED)
			));
		}

		RollingTimingWindow.Snapshot frame = SiliciumXMetrics.clientFrameSnapshot();
		if (frame.samples() > 0) {
			out.accept(line(
				"Client Frame: ",
				String.format(
					Locale.ROOT,
					"mean=%.2fms | p95=%.2fms | p99=%.2fms | ~%.0f FPS",
					frame.meanMs(), frame.p95Ms(), frame.p99Ms(), frame.estimatedFps()
				),
				ChatFormatting.AQUA
			));
		}

		out.accept(Component.literal("Subcommands: /siliciumx <status|modules|compat|metrics|bench|reload>")
			.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int executeModules(Consumer<Component> out) {
		ModCompatibility compat = ModCompatibility.get();
		out.accept(header("SiliciumX Modules (" + SiliciumXModule.values().length + ")"));

		for (ModCompatibility.ModuleResolution res : compat.allResolutions().values()) {
			SiliciumXModule m = res.module();
			MutableComponent badge = switch (res.state()) {
				case ACTIVE -> Component.literal("[ACTIVE] ").withStyle(ChatFormatting.GREEN);
				case FORCED_ACTIVE -> Component.literal("[FORCE] ").withStyle(ChatFormatting.AQUA);
				case YIELDED_TO_MOD -> Component.literal("[YIELD -> " + res.yieldedToMod() + "] ").withStyle(ChatFormatting.GOLD);
				case DISABLED_BY_CONFIG -> Component.literal("[OFF] ").withStyle(ChatFormatting.DARK_GRAY);
			};
			MutableComponent msg = badge
				.append(Component.literal(m.id()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" (" + m.category() + "/" + m.environment() + ")").withStyle(ChatFormatting.GRAY));
			if (res.note() != null) {
				msg.append(Component.literal(" — " + res.note()).withStyle(ChatFormatting.YELLOW));
			}
			out.accept(msg);
		}
		return 1;
	}

	private static int executeCompat(Consumer<Component> out) {
		ModCompatibility compat = ModCompatibility.get();
		List<ModCompatibility.KnownMod> detected = compat.detectedMods();
		out.accept(header("SiliciumX Universal Compatibility"));

		if (detected.isEmpty()) {
			out.accept(Component.literal("Running in standalone mode — all enabled SiliciumX modules are active.")
				.withStyle(ChatFormatting.GREEN));
		} else {
			out.accept(Component.literal("Detected ecosystem mods (" + detected.size() + "):")
				.withStyle(ChatFormatting.AQUA));
			for (ModCompatibility.KnownMod mod : detected) {
				out.accept(Component.literal(" - ")
					.withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(mod.displayName()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" (" + mod.modId() + "): ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(mod.domain()).withStyle(ChatFormatting.GRAY)));
			}
		}

		int yielded = compat.countByState(ModCompatibility.State.YIELDED_TO_MOD);
		if (yielded > 0) {
			out.accept(Component.literal("Auto-yielded modules (" + yielded + ") to avoid conflicts:")
				.withStyle(ChatFormatting.GOLD));
			for (ModCompatibility.ModuleResolution res : compat.allResolutions().values()) {
				if (res.state() == ModCompatibility.State.YIELDED_TO_MOD) {
					out.accept(Component.literal(" - " + res.module().id() + " -> " + res.yieldedToMod())
						.withStyle(ChatFormatting.YELLOW));
				}
			}
		} else {
			out.accept(Component.literal("Zero module conflicts detected.").withStyle(ChatFormatting.GREEN));
		}
		return 1;
	}

	private static int executeMetrics(Consumer<Component> out, boolean remoteServer) {
		out.accept(header("SiliciumX Live Telemetry"));

		RollingTimingWindow.Snapshot tick = SiliciumXMetrics.serverTickSnapshot();
		if (remoteServer) {
			out.accept(line("Server MSPT: ", "remote server (client metrics shown below)", ChatFormatting.GRAY));
		} else if (tick.samples() > 0) {
			out.accept(line(
				"Server MSPT (" + tick.samples() + "): ",
				String.format(
					Locale.ROOT,
					"mean=%.2fms min=%.2fms p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms (TPS %.1f)",
					tick.meanMs(), tick.minMs(), tick.p50Ms(), tick.p95Ms(), tick.p99Ms(), tick.maxMs(), tick.estimatedTps()
				),
				ChatFormatting.GREEN
			));
		} else {
			out.accept(line("Server MSPT: ", "collecting samples...", ChatFormatting.GRAY));
		}

		RollingTimingWindow.Snapshot frame = SiliciumXMetrics.clientFrameSnapshot();
		if (frame.samples() > 0) {
			out.accept(line(
				"Client Frame (" + frame.samples() + "): ",
				String.format(
					Locale.ROOT,
					"mean=%.2fms p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms (~%.0f FPS)",
					frame.meanMs(), frame.p50Ms(), frame.p95Ms(), frame.p99Ms(), frame.maxMs(), frame.estimatedFps()
				),
				ChatFormatting.AQUA
			));
		}

		Map<SiliciumXMetrics.Counter, Long> counters = SiliciumXMetrics.snapshotCounters();
		boolean anyCounter = false;
		for (Map.Entry<SiliciumXMetrics.Counter, Long> entry : counters.entrySet()) {
			long val = entry.getValue();
			if (val > 0L) {
				anyCounter = true;
				out.accept(Component.literal(" - " + entry.getKey().label() + ": ")
					.withStyle(ChatFormatting.GRAY)
					.append(Component.literal(String.format(Locale.ROOT, "%,d", val)).withStyle(ChatFormatting.WHITE)));
			}
		}
		if (!anyCounter) {
			out.accept(Component.literal("No subsystem counters incremented yet.").withStyle(ChatFormatting.DARK_GRAY));
		}
		return 1;
	}

	private static int executeMetricsReset(Consumer<Component> out) {
		SiliciumXMetrics.reset();
		out.accept(Component.literal("[SiliciumX] Telemetry windows and counters reset.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int executeBench(Consumer<Component> out) {
		out.accept(header("SiliciumX In-JVM Microbenchmark"));
		SiliciumXMetrics.BenchmarkReport report = SiliciumXMetrics.runQuickBenchmark();

		out.accept(line(
			"Biome Voronoi: ",
			String.format(
				Locale.ROOT,
				"Vanilla %.1f ns/op -> SiliciumX %.1f ns/op (%.2fx speedup)",
				report.vanillaVoronoiNsPerOp(),
				report.siliciumxVoronoiNsPerOp(),
				report.voronoiSpeedup()
			),
			ChatFormatting.GREEN
		));
		out.accept(line(
			"CompactSineLut (64 KB): ",
			String.format(Locale.ROOT, "%.2f ns/op", report.siliciumxSineNsPerOp()),
			ChatFormatting.AQUA
		));
		out.accept(line(
			"ShapePairCache (0-alloc): ",
			String.format(Locale.ROOT, "%.2f ns/op", report.shapeCacheNsPerOp()),
			ChatFormatting.AQUA
		));
		out.accept(line(
			"FastLongCache (128-slot): ",
			String.format(Locale.ROOT, "%.2f ns/op", report.chunkCacheNsPerOp()),
			ChatFormatting.AQUA
		));
		out.accept(line(
			"BoundedStringInterner: ",
			String.format(Locale.ROOT, "%.2f ns/op", report.internerNsPerOp()),
			ChatFormatting.AQUA
		));
		out.accept(line(
			"FastPerlinNoise (bit-exact): ",
			String.format(Locale.ROOT, "%.2f ns/op", report.siliciumxPerlinNsPerOp()),
			ChatFormatting.AQUA
		));
		return 1;
	}

	private static int executeReload(Consumer<Component> out) {
		SiliciumXConfig cfg = SiliciumXConfig.reloadRuntimeSettings();
		out.accept(Component.literal("[SiliciumX] Reloaded runtime tuning settings from config/siliciumx.json.")
			.withStyle(ChatFormatting.GREEN));
		out.accept(Component.literal(String.format(
			Locale.ROOT,
			"particleDist=%.0f (phys=%.0f) | signDist=%.0f | shadowDist=%.0f | weatherBehind=%s | soundCap=%d",
			cfg.particleMaxRenderDistance,
			cfg.particlePhysicsMaxDistance,
			cfg.signTextMaxRenderDistance,
			cfg.entityShadowMaxDistance,
			cfg.cullWeatherBehindCamera,
			cfg.maxIdenticalSoundsPerTick
		)).withStyle(ChatFormatting.GRAY));
		out.accept(Component.literal("Note: Enabling or disabling Mixin modules requires a game/server restart.")
			.withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static MutableComponent header(String title) {
		return Component.literal("=== " + title + " ===").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
	}

	private static MutableComponent line(String label, String value, ChatFormatting valueColor) {
		return Component.literal(label).withStyle(ChatFormatting.GRAY)
			.append(Component.literal(value).withStyle(valueColor));
	}
}
