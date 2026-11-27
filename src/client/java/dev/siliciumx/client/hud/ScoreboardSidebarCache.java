package dev.siliciumx.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Per-HUD cache for the sidebar scoreboard layout, eliminating per-frame {@code Stream} pipelines,
 * sorting, {@code PlayerTeam.formatNameForTeam} component copies, and repeated {@code Font.width}
 * measurements on multiplayer servers.
 *
 * <p>Invalidated immediately on any {@link Scoreboard} mutation via {@code scoreboardEpoch}, font
 * reload, language change, objective/format switch, or a conservative safety TTL.</p>
 */
public final class ScoreboardSidebarCache {
	private static final int MAX_SIDEBAR_ENTRIES = 15;
	private static final long SAFETY_TTL_MS = 1000L;

	private Objective cachedObjective;
	private Scoreboard cachedScoreboard;
	private Component cachedTitle;
	private NumberFormat cachedNumberFormat;
	private long cachedScoreboardEpoch = Long.MIN_VALUE;
	private int cachedFontEpoch = Integer.MIN_VALUE;
	private Language cachedLanguage;
	private long lastBuildMs;

	private int titleWidth;
	private int maxWidth;
	private Row[] rows = new Row[0];

	public boolean isValid(
		Objective objective,
		Scoreboard scoreboard,
		long scoreboardEpoch,
		int fontEpoch,
		Language language,
		long nowMs
	) {
		if (this.cachedObjective != objective || this.cachedScoreboard != scoreboard) {
			return false;
		}
		if (this.cachedScoreboardEpoch != scoreboardEpoch
			|| this.cachedFontEpoch != fontEpoch
			|| this.cachedLanguage != language
		) {
			return false;
		}
		if (this.cachedTitle != objective.getDisplayName()) {
			return false;
		}
		NumberFormat currentFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		if (this.cachedNumberFormat != currentFormat) {
			return false;
		}
		return (nowMs - this.lastBuildMs) < SAFETY_TTL_MS;
	}

	public void rebuild(
		Objective objective,
		Scoreboard scoreboard,
		Comparator<PlayerScoreEntry> displayOrder,
		Font font,
		long scoreboardEpoch,
		int fontEpoch,
		Language language,
		long nowMs
	) {
		NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		Collection<PlayerScoreEntry> allScores = scoreboard.listPlayerScores(objective);
		List<PlayerScoreEntry> visible = new ArrayList<>(Math.min(allScores.size(), 32));
		for (PlayerScoreEntry entry : allScores) {
			if (!entry.isHidden()) {
				visible.add(entry);
			}
		}
		visible.sort(displayOrder);

		int count = Math.min(visible.size(), MAX_SIDEBAR_ENTRIES);
		Row[] builtRows = new Row[count];

		Component title = objective.getDisplayName();
		int titleW = font.width(title);
		int maxW = titleW;
		int separatorW = font.width(":");

		for (int i = 0; i < count; i++) {
			PlayerScoreEntry entry = visible.get(i);
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component ownerName = entry.ownerName();
			MutableComponent formattedName = PlayerTeam.formatNameForTeam(team, ownerName);
			MutableComponent formattedScore = entry.formatValue(format);
			int scoreW = font.width(formattedScore);
			int rowW = font.width(formattedName) + (scoreW > 0 ? separatorW + scoreW : 0);
			if (rowW > maxW) {
				maxW = rowW;
			}
			builtRows[i] = new Row(formattedName, formattedScore, scoreW);
		}

		this.cachedObjective = objective;
		this.cachedScoreboard = scoreboard;
		this.cachedTitle = title;
		this.cachedNumberFormat = format;
		this.cachedScoreboardEpoch = scoreboardEpoch;
		this.cachedFontEpoch = fontEpoch;
		this.cachedLanguage = language;
		this.lastBuildMs = nowMs;
		this.titleWidth = titleW;
		this.maxWidth = maxW;
		this.rows = builtRows;
	}

	public Component title() {
		return this.cachedTitle;
	}

	public int titleWidth() {
		return this.titleWidth;
	}

	public int maxWidth() {
		return this.maxWidth;
	}

	public Row[] rows() {
		return this.rows;
	}

	public record Row(Component name, Component score, int scoreWidth) {
	}
}
