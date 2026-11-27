package dev.siliciumx.client.gui;

import dev.siliciumx.compat.ModCompatibility;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.module.SiliciumXModule;
import dev.siliciumx.util.RollingTimingWindow;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Native Minecraft 26.3 configuration screen for SiliciumX (accessible from Mod Menu without
 * requiring Cloth Config or YACL).
 *
 * <p>Provides four tabbed views: General & Compatibility, Modules ({@code auto/force/off} with
 * live runtime status badges), Live Runtime Tuning sliders, and Live Telemetry counters.</p>
 */
public final class SiliciumXConfigScreen extends Screen {
	private static final int HEADER_HEIGHT = 56;
	private static final int FOOTER_HEIGHT = 36;
	private static final int ROW_HEIGHT = 28;
	private static final int COLOR_WHITE = 0xFFFFFFFF;
	private static final int COLOR_MUTED = 0xFFA8B4C4;
	private static final int COLOR_ACCENT = 0xFF40D6F5;
	private static final int COLOR_WARN = 0xFFFFD166;
	private static final int COLOR_ROW_BG = 0x44101824;

	private final Screen parent;
	private SiliciumXConfig draft;
	private Tab activeTab = Tab.MODULES;
	private OptionList optionList;
	private Button resetCountersButton;
	private boolean restartPending;
	private long lastTelemetryRefreshMs;

	public SiliciumXConfigScreen(Screen parent) {
		super(Component.literal("SiliciumX Configuration"));
		this.parent = parent;
		this.draft = SiliciumXConfig.get().copy();
	}

	@Override
	protected void init() {
		this.clearWidgets();
		updateRestartPendingFlag();

		// Top category tabs
		Tab[] tabs = Tab.values();
		int tabGap = 6;
		int tabWidth = Math.min(104, (this.width - 32 - tabGap * (tabs.length - 1)) / tabs.length);
		int totalTabWidth = tabWidth * tabs.length + tabGap * (tabs.length - 1);
		int tabStartX = (this.width - totalTabWidth) / 2;
		int tabY = 30;

		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			Button btn = Button.builder(Component.literal(tab.label), b -> switchTab(tab))
				.bounds(tabStartX + i * (tabWidth + tabGap), tabY, tabWidth, 20)
				.build();
			btn.active = (tab != this.activeTab);
			this.addRenderableWidget(btn);
		}

		// Scrollable main content area
		int listTop = HEADER_HEIGHT;
		int listHeight = Math.max(40, this.height - HEADER_HEIGHT - FOOTER_HEIGHT);
		this.optionList = new OptionList(this.minecraft, this.width, listHeight, listTop, ROW_HEIGHT);
		populateActiveTab();
		this.addRenderableWidget(this.optionList);

		// Bottom footer bar: Reset Defaults | Cancel | Save & Close
		int footerY = this.height - 28;
		int btnWidth = 104;
		int gap = 8;
		int totalFooterWidth = btnWidth * 3 + gap * 2;
		int footerStartX = (this.width - totalFooterWidth) / 2;

		this.addRenderableWidget(
			Button.builder(Component.literal("Reset Defaults"), b -> {
				this.draft = new SiliciumXConfig();
				this.init();
			})
				.bounds(footerStartX, footerY, btnWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Restore all SiliciumX modules and tuning values to defaults.")))
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.bounds(footerStartX + btnWidth + gap, footerY, btnWidth, 20)
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.literal("Save & Close"), b -> {
				SiliciumXConfig.applyAndSave(this.draft);
				this.onClose();
			})
				.bounds(footerStartX + (btnWidth + gap) * 2, footerY, btnWidth, 20)
				.tooltip(Tooltip.create(Component.literal(
					"Save to config/siliciumx.json. Tuning changes apply immediately; module mode changes apply on restart."
				)))
				.build()
		);
	}

	private void switchTab(Tab newTab) {
		if (this.activeTab != newTab) {
			this.activeTab = newTab;
			this.init();
		}
	}

	private void updateRestartPendingFlag() {
		this.restartPending = SiliciumXConfig.isRestartRequired(this.draft);
	}

	private void populateActiveTab() {
		switch (this.activeTab) {
			case GENERAL -> populateGeneralTab();
			case MODULES -> populateModulesTab();
			case TUNING -> populateTuningTab();
			case TELEMETRY -> populateTelemetryTab();
		}
	}

	private void populateGeneralTab() {
		this.optionList.addSectionHeader("Compatibility & Startup");

		this.optionList.addToggleRow(
			"Auto Compatibility Mode",
			"Automatically yields modules with exact overlaps to installed optimization mods (restart required).",
			this.draft.autoCompatibilityMode,
			val -> {
				this.draft.autoCompatibilityMode = val;
				updateRestartPendingFlag();
			}
		);

		this.optionList.addToggleRow(
			"Respect Companion Configs",
			"Reads config/lithium.properties so SiliciumX automatically steps in if you disable a Lithium rule (restart required).",
			this.draft.respectCompanionConfigs,
			val -> {
				this.draft.respectCompanionConfigs = val;
				updateRestartPendingFlag();
			}
		);

		this.optionList.addToggleRow(
			"Log Startup Compatibility Report",
			"Prints detected ecosystem mods and active/yielded SiliciumX modules in the game log.",
			this.draft.logCompatibilityReport,
			val -> this.draft.logCompatibilityReport = val
		);

		ModCompatibility compat = ModCompatibility.get();
		List<ModCompatibility.KnownMod> detected = compat.detectedMods();
		this.optionList.addSectionHeader("Detected Companion Ecosystem (" + detected.size() + ")");

		if (detected.isEmpty()) {
			this.optionList.addInfoRow(
				Component.literal("Standalone Mode").withStyle(ChatFormatting.GREEN),
				Component.literal("All enabled SiliciumX modules run without companion overlaps.").withStyle(ChatFormatting.GRAY)
			);
		} else {
			for (ModCompatibility.KnownMod mod : detected) {
				this.optionList.addInfoRow(
					Component.literal(mod.displayName()).withStyle(ChatFormatting.AQUA),
					Component.literal(mod.domain()).withStyle(ChatFormatting.GRAY)
				);
			}
		}
	}

	private void populateModulesTab() {
		ModCompatibility compat = ModCompatibility.get();

		for (SiliciumXModule.Category category : SiliciumXModule.Category.values()) {
			this.optionList.addSectionHeader(categoryLabel(category));
			for (SiliciumXModule module : SiliciumXModule.values()) {
				if (module.category() != category) {
					continue;
				}
				ModCompatibility.ModuleResolution resolution = compat.resolution(module);
				this.optionList.addModuleRow(module, resolution, this.draft.moduleMode(module), mode -> {
					this.draft.setModuleMode(module, mode);
					updateRestartPendingFlag();
				});
			}
		}
	}

	private void populateTuningTab() {
		this.optionList.addSectionHeader("GPU & Particle Culling (Live Reload)");

		this.optionList.addSliderRow(
			"Particle Max Distance",
			"Maximum distance (blocks) to spawn and extract quad particles before culling.",
			16.0, 256.0, 4.0,
			this.draft.particleMaxRenderDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.particleMaxRenderDistance = v
		);

		this.optionList.addSliderRow(
			"Particle Physics Max Distance",
			"Maximum distance (blocks) at which particles run VoxelShape block-collision physics.",
			8.0, 128.0, 2.0,
			this.draft.particlePhysicsMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.particlePhysicsMaxDistance = v
		);

		this.optionList.addToggleRow(
			"Cull Particles In Solid Blocks",
			"Skips rendering particles completely enclosed inside opaque full-cube blocks.",
			this.draft.cullParticlesInsideOpaqueBlocks,
			val -> this.draft.cullParticlesInsideOpaqueBlocks = val
		);

		this.optionList.addSliderRow(
			"Entity Cull Min Distance",
			"Entities closer than this distance are never occlusion-culled.",
			4.0, 64.0, 1.0,
			this.draft.entityCullingMinDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.entityCullingMinDistance = v
		);

		this.optionList.addSliderRow(
			"Block Entity Cull Min Distance",
			"Block entities closer than this distance are never occlusion-culled.",
			4.0, 64.0, 1.0,
			this.draft.blockEntityCullingMinDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.blockEntityCullingMinDistance = v
		);

		this.optionList.addSliderRow(
			"Sign Text Max Distance",
			"Maximum distance (blocks) to render sign text before skipping unreadable text passes.",
			8.0, 128.0, 4.0,
			this.draft.signTextMaxRenderDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.signTextMaxRenderDistance = v
		);

		this.optionList.addSliderRow(
			"Entity Shadow Max Distance",
			"Maximum distance (blocks) to extract entity blob shadows (16 = Vanilla full distance).",
			4.0, 16.0, 1.0,
			this.draft.entityShadowMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.entityShadowMaxDistance = v
		);

		this.optionList.addSliderRow(
			"NameTag Max Distance",
			"Maximum distance (blocks) to render floating entity nametags (crosshair target is always shown).",
			8.0, 64.0, 4.0,
			this.draft.nameTagMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.nameTagMaxDistance = v
		);

		this.optionList.addSliderRow(
			"Cosmetic Layer Max Distance",
			"Maximum distance (blocks) to render capes, held items, and non-player mob armor (player armor stays visible).",
			12.0, 128.0, 4.0,
			this.draft.cosmeticLayerMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.cosmeticLayerMaxDistance = v
		);

		this.optionList.addSliderRow(
			"Item Glint Max Distance",
			"Maximum distance (blocks) to render enchantment glint on dropped items and non-map item frames.",
			6.0, 128.0, 2.0,
			this.draft.itemGlintMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.itemGlintMaxDistance = v
		);

		this.optionList.addSliderRow(
			"Item Frame Model Max Distance",
			"Maximum distance (blocks) to render interior item models inside non-map item frames (maps are never culled).",
			12.0, 128.0, 4.0,
			this.draft.itemFrameItemMaxDistance,
			v -> String.format(Locale.ROOT, "%.0f blocks", v),
			v -> this.draft.itemFrameItemMaxDistance = v
		);

		this.optionList.addToggleRow(
			"Simplify Distant Item Clusters",
			"Renders distant stacked dropped items as a single model beyond Item Glint Max Distance.",
			this.draft.simplifyDistantItemStacks,
			val -> this.draft.simplifyDistantItemStacks = val
		);

		this.optionList.addToggleRow(
			"Cull Weather Behind Camera",
			"Skips rain/snow columns strictly behind the camera plane with a conservative margin.",
			this.draft.cullWeatherBehindCamera,
			val -> this.draft.cullWeatherBehindCamera = val
		);

		this.optionList.addSliderRow(
			"Occlusion Max Ray Steps",
			"Maximum voxel steps per DDA visibility ray (higher = deeper culling, slightly more CPU).",
			8.0, 128.0, 4.0,
			this.draft.occlusionMaxRaySteps,
			v -> String.format(Locale.ROOT, "%d steps", (int) Math.round(v)),
			v -> this.draft.occlusionMaxRaySteps = (int) Math.round(v)
		);

		this.optionList.addSliderRow(
			"Occlusion Cache TTL",
			"Frames to reuse cached entity/block-entity occlusion status when the camera is steady.",
			1.0, 10.0, 1.0,
			this.draft.occlusionCacheFrames,
			v -> String.format(Locale.ROOT, "%d frames", (int) Math.round(v)),
			v -> this.draft.occlusionCacheFrames = (int) Math.round(v)
		);

		this.optionList.addSliderRow(
			"Lightmap Skip Epsilon",
			"Skip redundant GPU lightmap render passes when input change is below this threshold (1/255 = 0.0039).",
			0.0, 0.01, 0.0005,
			this.draft.lightmapEpsilon,
			v -> String.format(Locale.ROOT, "%.4f", v),
			v -> this.draft.lightmapEpsilon = v.floatValue()
		);

		this.optionList.addSectionHeader("HUD, Audio & Memory Tuning");

		this.optionList.addSliderRow(
			"F3 HUD Refresh Interval",
			"Milliseconds between F3 text entry regenerations (0 = every frame, 50 ms = 20 Hz).",
			0.0, 500.0, 10.0,
			this.draft.debugHudUpdateIntervalMs,
			v -> v <= 0.5 ? "Every frame" : String.format(Locale.ROOT, "%d ms", (int) Math.round(v)),
			v -> this.draft.debugHudUpdateIntervalMs = (int) Math.round(v)
		);

		this.optionList.addSliderRow(
			"Scoreboard & Tab Cache Interval",
			"Milliseconds between Tab list re-sorts (0 = disable HUD cache; scoreboard invalidates immediately on edits).",
			0.0, 500.0, 10.0,
			this.draft.hudCacheUpdateIntervalMs,
			v -> v <= 0.5 ? "Disabled" : String.format(Locale.ROOT, "%d ms", (int) Math.round(v)),
			v -> this.draft.hudCacheUpdateIntervalMs = (int) Math.round(v)
		);

		this.optionList.addToggleRow(
			"Show [SiliciumX] F3 Summary",
			"Appends a compact one-line SiliciumX status readout to the F3 debug screen.",
			this.draft.showF3Summary,
			val -> this.draft.showF3Summary = val
		);

		this.optionList.addSliderRow(
			"Max Identical Sounds / Tick",
			"Cap identical one-shot sound bursts per client tick to prevent OpenAL stalls (0 = unlimited).",
			0.0, 32.0, 1.0,
			this.draft.maxIdenticalSoundsPerTick,
			v -> v <= 0.5 ? "Unlimited" : String.format(Locale.ROOT, "%d / tick", (int) Math.round(v)),
			v -> this.draft.maxIdenticalSoundsPerTick = (int) Math.round(v)
		);

		this.optionList.addSliderRow(
			"Chunk Lookup Cache Size",
			"Slots in ServerChunkCache's fast FULL-chunk cache (power-of-two rounded, restart required).",
			16.0, 1024.0, 16.0,
			this.draft.chunkLookupCacheSize,
			v -> String.format(Locale.ROOT, "%d slots", (int) Math.round(v)),
			v -> {
				this.draft.chunkLookupCacheSize = (int) Math.round(v);
				updateRestartPendingFlag();
			}
		);
	}

	private void populateTelemetryTab() {
		this.lastTelemetryRefreshMs = Util.getMillis();
		this.optionList.addSectionHeader("Live Performance Timings");

		RollingTimingWindow.Snapshot frame = SiliciumXMetrics.clientFrameSnapshot();
		if (frame.samples() > 0) {
			this.optionList.addInfoRow(
				Component.literal("Client Frame Time (" + frame.samples() + " frames)").withStyle(ChatFormatting.AQUA),
				Component.literal(String.format(
					Locale.ROOT,
					"mean=%.2fms | p95=%.2fms | p99=%.2fms | ~%.0f FPS",
					frame.meanMs(), frame.p95Ms(), frame.p99Ms(), frame.estimatedFps()
				)).withStyle(ChatFormatting.WHITE)
			);
		} else {
			this.optionList.addInfoRow(
				Component.literal("Client Frame Time").withStyle(ChatFormatting.AQUA),
				Component.literal("Collecting samples...").withStyle(ChatFormatting.GRAY)
			);
		}

		RollingTimingWindow.Snapshot tick = SiliciumXMetrics.serverTickSnapshot();
		boolean inSingleplayer = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
		boolean inRemoteServer = this.minecraft != null && this.minecraft.level != null && !inSingleplayer;
		if (inRemoteServer) {
			this.optionList.addInfoRow(
				Component.literal("Server MSPT").withStyle(ChatFormatting.GREEN),
				Component.literal("Remote multiplayer server (client optimizations active).").withStyle(ChatFormatting.GRAY)
			);
		} else if (tick.samples() > 0) {
			this.optionList.addInfoRow(
				Component.literal("Server MSPT (" + tick.samples() + " ticks)").withStyle(ChatFormatting.GREEN),
				Component.literal(String.format(
					Locale.ROOT,
					"mean=%.2fms | p50=%.2fms | p95=%.2fms | TPS=%.1f",
					tick.meanMs(), tick.p50Ms(), tick.p95Ms(), tick.estimatedTps()
				)).withStyle(ChatFormatting.WHITE)
			);
		} else {
			this.optionList.addInfoRow(
				Component.literal("Server MSPT").withStyle(ChatFormatting.GREEN),
				Component.literal("Join a singleplayer world to sample integrated server MSPT.").withStyle(ChatFormatting.GRAY)
			);
		}

		if (this.resetCountersButton == null) {
			this.resetCountersButton = Button.builder(Component.literal("Reset Counters"), b -> {
				SiliciumXMetrics.reset();
				if (this.optionList != null) {
					this.optionList.clearAllEntries();
					populateTelemetryTab();
					this.optionList.setScrollAmount(0.0);
				}
			})
				.bounds(0, 0, 110, 20)
				.tooltip(Tooltip.create(Component.literal("Reset all rolling timing windows and optimization hit counters.")))
				.build();
		}
		this.optionList.addControlRow("Telemetry Counters", this.resetCountersButton);

		this.optionList.addSectionHeader("Optimization Hit Counters");
		Map<SiliciumXMetrics.Counter, Long> counters = SiliciumXMetrics.snapshotCounters();
		boolean any = false;
		for (Map.Entry<SiliciumXMetrics.Counter, Long> entry : counters.entrySet()) {
			long count = entry.getValue();
			if (count > 0L) {
				any = true;
				this.optionList.addInfoRow(
					Component.literal(entry.getKey().label()).withStyle(ChatFormatting.GRAY),
					Component.literal(String.format(Locale.ROOT, "%,d", count)).withStyle(ChatFormatting.WHITE)
				);
			}
		}
		if (!any) {
			this.optionList.addInfoRow(
				Component.literal("No counters recorded yet").withStyle(ChatFormatting.DARK_GRAY),
				Component.literal("Counters increment live while playing in a world.").withStyle(ChatFormatting.GRAY)
			);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (this.activeTab == Tab.TELEMETRY && this.optionList != null) {
			long now = Util.getMillis();
			if (now - this.lastTelemetryRefreshMs >= 500L) {
				double scroll = this.optionList.scrollAmount();
				this.optionList.clearAllEntries();
				populateTelemetryTab();
				this.optionList.setScrollAmount(scroll);
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Header title
		graphics.centeredText(
			this.font,
			Component.literal("SiliciumX Configuration").withStyle(ChatFormatting.BOLD),
			this.width / 2,
			6,
			COLOR_ACCENT
		);

		// Live status subtitle
		ModCompatibility compat = ModCompatibility.get();
		String status = String.format(
			Locale.ROOT,
			"%d/%d active · %d auto-yielded%s",
			compat.countActive(),
			SiliciumXModule.values().length,
			compat.countByState(ModCompatibility.State.YIELDED_TO_MOD),
			compat.isModDetected("sodium") ? " · Sodium complementary" : ""
		);
		if (this.restartPending) {
			graphics.centeredText(
				this.font,
				Component.literal(status + " · Restart required for module changes"),
				this.width / 2,
				18,
				COLOR_WARN
			);
		} else {
			graphics.centeredText(this.font, Component.literal(status), this.width / 2, 18, COLOR_MUTED);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	private static String categoryLabel(SiliciumXModule.Category category) {
		return switch (category) {
			case CPU -> "CPU Optimizations (Logic, Tick & Allocations)";
			case MEMORY -> "Memory Optimizations (RAM & GC Pressure)";
			case GPU -> "GPU & Render Optimizations (Sodium-Friendly)";
			case DIAGNOSTICS -> "Telemetry & Diagnostics";
		};
	}

	private enum Tab {
		GENERAL("General"),
		MODULES("Modules"),
		TUNING("Tuning"),
		TELEMETRY("Telemetry");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private final class OptionList extends ContainerObjectSelectionList<OptionList.Row> {
		OptionList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
			super(minecraft, width, height, y, itemHeight);
		}

		@Override
		public int getRowWidth() {
			return Math.min(440, this.width - 36);
		}

		void clearAllEntries() {
			this.clearEntries();
		}

		void addSectionHeader(String title) {
			this.addEntry(new SectionHeaderRow(title));
		}

		void addToggleRow(String label, String tooltip, boolean initial, Consumer<Boolean> onChange) {
			CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
				.displayOnlyValue()
				.withTooltip(val -> Tooltip.create(Component.literal(tooltip)))
				.create(0, 0, 84, 20, Component.literal(label), (btn, val) -> onChange.accept(val));
			this.addEntry(new ControlRow(Component.literal(label), null, button));
		}

		void addModuleRow(
			SiliciumXModule module,
			ModCompatibility.ModuleResolution resolution,
			SiliciumXModule.Mode currentMode,
			Consumer<SiliciumXModule.Mode> onChange
		) {
			StringBuilder tip = new StringBuilder(module.description());
			if (!module.yieldMods().isEmpty()) {
				tip.append("\n\nAuto-yields to: ").append(String.join(", ", module.yieldMods()));
			}
			if (resolution != null && resolution.note() != null) {
				tip.append("\nStatus note: ").append(resolution.note());
			}
			tip.append("\n\nModes: AUTO (smart compat) | FORCE (always on) | OFF (disabled). Restart required.");

			CycleButton<SiliciumXModule.Mode> button = CycleButton.builder(
					SiliciumXConfigScreen::formatMode,
					currentMode
				)
				.withValues(SiliciumXModule.Mode.values())
				.displayOnlyValue()
				.withTooltip(mode -> Tooltip.create(Component.literal(tip.toString())))
				.create(0, 0, 84, 20, Component.literal(module.id()), (btn, val) -> onChange.accept(val));

			Component statusBadge = formatResolutionBadge(resolution);
			this.addEntry(new ControlRow(Component.literal(module.id()), statusBadge, button));
		}

		void addSliderRow(
			String label,
			String tooltip,
			double min,
			double max,
			double step,
			double initial,
			Function<Double, String> formatter,
			Consumer<Double> onChange
		) {
			ValueSlider slider = new ValueSlider(0, 0, 132, 20, min, max, step, initial, formatter, onChange);
			slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
			this.addEntry(new ControlRow(Component.literal(label), null, slider));
		}

		void addControlRow(String label, AbstractWidget control) {
			this.addEntry(new ControlRow(Component.literal(label), null, control));
		}

		void addInfoRow(Component left, Component right) {
			this.addEntry(new InfoRow(left, right));
		}

		abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
		}

		final class SectionHeaderRow extends Row {
			private final Component title;

			SectionHeaderRow(String title) {
				this.title = Component.literal(title).withStyle(ChatFormatting.BOLD);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();
				graphics.text(SiliciumXConfigScreen.this.font, this.title, x + 2, y + 10, COLOR_ACCENT);
				graphics.fill(x, y + 22, x + w, y + 23, 0x5540D6F5);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}

		final class ControlRow extends Row {
			private final Component label;
			private final Component badge;
			private final AbstractWidget control;

			ControlRow(Component label, Component badge, AbstractWidget control) {
				this.label = label;
				this.badge = badge;
				this.control = control;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();
				int h = this.getContentHeight();

				if (hovered) {
					graphics.fill(x, y, x + w, y + h, COLOR_ROW_BG);
				}

				int controlX = x + w - this.control.getWidth() - 2;
				int controlY = y + (h - this.control.getHeight()) / 2;
				this.control.setX(controlX);
				this.control.setY(controlY);
				this.control.extractRenderState(graphics, mouseX, mouseY, partialTick);

				int textY = y + (h - 9) / 2;
				graphics.text(SiliciumXConfigScreen.this.font, this.label, x + 4, textY, COLOR_WHITE);

				if (this.badge != null) {
					int badgeWidth = SiliciumXConfigScreen.this.font.width(this.badge);
					int badgeX = controlX - badgeWidth - 8;
					int labelEnd = x + 4 + SiliciumXConfigScreen.this.font.width(this.label) + 6;
					if (badgeX >= labelEnd) {
						graphics.text(SiliciumXConfigScreen.this.font, this.badge, badgeX, textY, COLOR_WHITE);
					}
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(this.control);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(this.control);
			}
		}

		final class InfoRow extends Row {
			private final Component left;
			private final Component right;

			InfoRow(Component left, Component right) {
				this.left = left;
				this.right = right;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();
				int h = this.getContentHeight();

				int textY = y + (h - 9) / 2;
				graphics.text(SiliciumXConfigScreen.this.font, this.left, x + 4, textY, COLOR_WHITE);

				int rightW = SiliciumXConfigScreen.this.font.width(this.right);
				int leftEnd = x + 4 + SiliciumXConfigScreen.this.font.width(this.left) + 8;
				int rightX = Math.max(leftEnd, x + w - rightW - 4);
				graphics.text(SiliciumXConfigScreen.this.font, this.right, rightX, textY, COLOR_MUTED);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}
	}

	private static Component formatMode(SiliciumXModule.Mode mode) {
		return switch (mode) {
			case AUTO -> Component.literal("AUTO").withStyle(ChatFormatting.GREEN);
			case FORCE -> Component.literal("FORCE").withStyle(ChatFormatting.AQUA);
			case OFF -> Component.literal("OFF").withStyle(ChatFormatting.RED);
		};
	}

	private static Component formatResolutionBadge(ModCompatibility.ModuleResolution res) {
		if (res == null) {
			return Component.empty();
		}
		return switch (res.state()) {
			case ACTIVE -> Component.literal(res.note() != null ? "[TAKEOVER]" : "[ACTIVE]").withStyle(ChatFormatting.GREEN);
			case FORCED_ACTIVE -> Component.literal("[FORCED]").withStyle(ChatFormatting.AQUA);
			case YIELDED_TO_MOD -> Component.literal("[YIELD: " + res.yieldedToMod() + "]").withStyle(ChatFormatting.GOLD);
			case DISABLED_BY_CONFIG -> Component.literal("[OFF]").withStyle(ChatFormatting.DARK_GRAY);
		};
	}

	private static final class ValueSlider extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final double step;
		private final Function<Double, String> formatter;
		private final Consumer<Double> onChange;

		ValueSlider(
			int x,
			int y,
			int width,
			int height,
			double min,
			double max,
			double step,
			double initial,
			Function<Double, String> formatter,
			Consumer<Double> onChange
		) {
			super(x, y, width, height, Component.empty(), normalize(initial, min, max));
			this.min = min;
			this.max = max;
			this.step = step;
			this.formatter = formatter;
			this.onChange = onChange;
			updateMessage();
		}

		private static double normalize(double val, double min, double max) {
			if (max <= min) {
				return 0.0;
			}
			return Math.max(0.0, Math.min(1.0, (val - min) / (max - min)));
		}

		private double currentValue() {
			double raw = this.min + this.value * (this.max - this.min);
			if (this.step > 0.0) {
				raw = Math.round(raw / this.step) * this.step;
			}
			return Math.max(this.min, Math.min(this.max, raw));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(this.formatter.apply(currentValue())));
		}

		@Override
		protected void applyValue() {
			this.onChange.accept(currentValue());
		}
	}
}
