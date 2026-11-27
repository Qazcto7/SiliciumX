package dev.siliciumx.client.hud;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

import java.util.IdentityHashMap;

/**
 * Bounded per-overlay cache for formatted {@link PlayerInfo} display names in the multiplayer
 * Tab list, eliminating up to 80 {@code Component.copy()} / {@code PlayerTeam.formatNameForTeam()}
 * allocations per rendered frame while Tab is open.
 *
 * <p>Validates object identity on {@code tabListDisplayName}, {@code team}, and {@code gameMode},
 * and invalidates automatically on any scoreboard mutation, font reload, or language change.</p>
 */
public final class TabDisplayNameCache {
	private static final int MAX_ENTRIES = 256;

	private final IdentityHashMap<PlayerInfo, Entry> entries = new IdentityHashMap<>(96);
	private long lastScoreboardEpoch = Long.MIN_VALUE;
	private int lastFontEpoch = Integer.MIN_VALUE;
	private Language lastLanguage;

	public Component get(PlayerInfo info, long scoreboardEpoch, int fontEpoch, Language language) {
		if (scoreboardEpoch != this.lastScoreboardEpoch
			|| fontEpoch != this.lastFontEpoch
			|| language != this.lastLanguage
		) {
			this.entries.clear();
			this.lastScoreboardEpoch = scoreboardEpoch;
			this.lastFontEpoch = fontEpoch;
			this.lastLanguage = language;
			return null;
		}
		Entry entry = this.entries.get(info);
		if (entry == null) {
			return null;
		}
		if (entry.rawTabName == info.getTabListDisplayName()
			&& entry.team == info.getTeam()
			&& entry.gameMode == info.getGameMode()
		) {
			return entry.formatted;
		}
		return null;
	}

	public void put(PlayerInfo info, Component formatted) {
		if (info == null || formatted == null) {
			return;
		}
		if (this.entries.size() >= MAX_ENTRIES) {
			this.entries.clear();
		}
		this.entries.put(info, new Entry(info.getTabListDisplayName(), info.getTeam(), info.getGameMode(), formatted));
	}

	public void clear() {
		this.entries.clear();
		this.lastScoreboardEpoch = Long.MIN_VALUE;
		this.lastFontEpoch = Integer.MIN_VALUE;
		this.lastLanguage = null;
	}

	private record Entry(
		Component rawTabName,
		PlayerTeam team,
		GameType gameMode,
		Component formatted
	) {
	}
}
