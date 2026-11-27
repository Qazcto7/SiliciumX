package dev.siliciumx.client.mixin.hud;

import dev.siliciumx.client.SiliciumXClientState;
import dev.siliciumx.client.hud.TabDisplayNameCache;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;

/**
 * Caches sorted {@link PlayerTabOverlay#getPlayerInfos()} lists and formatted
 * {@link PlayerTabOverlay#getNameForDisplay(PlayerInfo)} components while Tab is open on busy
 * multiplayer servers, slashing Stream sorting and {@code Component.copy()} GC churn.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayCacheMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Unique
	private List<PlayerInfo> siliciumx$cachedPlayerInfos;
	@Unique
	private int siliciumx$cachedListedSize;
	@Unique
	private long siliciumx$cachedListEpoch;
	@Unique
	private long siliciumx$lastListRefreshMs;
	@Unique
	private TabDisplayNameCache siliciumx$nameCache;

	@Inject(
		method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/Hud;)V",
		at = @At("RETURN")
	)
	private void siliciumx$initTabCaches(Minecraft minecraft, Hud hud, CallbackInfo ci) {
		this.siliciumx$cachedListedSize = -1;
		this.siliciumx$nameCache = new TabDisplayNameCache();
	}

	@Unique
	private TabDisplayNameCache siliciumx$getOrCreateNameCache() {
		TabDisplayNameCache cache = this.siliciumx$nameCache;
		if (cache == null) {
			cache = new TabDisplayNameCache();
			this.siliciumx$nameCache = cache;
		}
		return cache;
	}

	@Inject(method = "getPlayerInfos()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
	private void siliciumx$lookupCachedPlayerInfos(CallbackInfoReturnable<List<PlayerInfo>> cir) {
		int intervalMs = SiliciumXConfig.get().hudCacheUpdateIntervalMs;
		if (intervalMs <= 0 || this.siliciumx$cachedPlayerInfos == null) {
			return;
		}
		if (this.minecraft.player == null || this.minecraft.player.connection == null) {
			return;
		}
		Collection<PlayerInfo> listed = this.minecraft.player.connection.getListedOnlinePlayers();
		long sbEpoch = SiliciumXClientState.scoreboardEpoch();
		long now = Util.getMillis();
		if (listed.size() == this.siliciumx$cachedListedSize
			&& sbEpoch == this.siliciumx$cachedListEpoch
			&& (now - this.siliciumx$lastListRefreshMs) < intervalMs
		) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.HUD_LAYOUTS_CACHED);
			cir.setReturnValue(this.siliciumx$cachedPlayerInfos);
		}
	}

	@Inject(method = "getPlayerInfos()Ljava/util/List;", at = @At("RETURN"))
	private void siliciumx$storeCachedPlayerInfos(CallbackInfoReturnable<List<PlayerInfo>> cir) {
		List<PlayerInfo> result = cir.getReturnValue();
		if (result == null || this.minecraft.player == null || this.minecraft.player.connection == null) {
			return;
		}
		this.siliciumx$cachedPlayerInfos = result;
		this.siliciumx$cachedListedSize = this.minecraft.player.connection.getListedOnlinePlayers().size();
		this.siliciumx$cachedListEpoch = SiliciumXClientState.scoreboardEpoch();
		this.siliciumx$lastListRefreshMs = Util.getMillis();
	}

	@Inject(
		method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$lookupCachedDisplayName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (info == null || SiliciumXConfig.get().hudCacheUpdateIntervalMs <= 0) {
			return;
		}
		Component cached = this.siliciumx$getOrCreateNameCache().get(
			info,
			SiliciumXClientState.scoreboardEpoch(),
			SiliciumXClientState.fontEpoch(),
			Language.getInstance()
		);
		if (cached != null) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.HUD_LAYOUTS_CACHED);
			cir.setReturnValue(cached);
		}
	}

	@Inject(
		method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
		at = @At("RETURN")
	)
	private void siliciumx$storeCachedDisplayName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (info != null && SiliciumXConfig.get().hudCacheUpdateIntervalMs > 0) {
			this.siliciumx$getOrCreateNameCache().put(info, cir.getReturnValue());
		}
	}

	@Inject(method = "setVisible(Z)V", at = @At("HEAD"))
	private void siliciumx$onSetVisible(boolean visible, CallbackInfo ci) {
		if (!visible) {
			this.siliciumx$clearCaches();
		}
	}

	@Inject(method = "reset()V", at = @At("HEAD"))
	private void siliciumx$onReset(CallbackInfo ci) {
		this.siliciumx$clearCaches();
	}

	@Unique
	private void siliciumx$clearCaches() {
		this.siliciumx$cachedPlayerInfos = null;
		this.siliciumx$cachedListedSize = -1;
		this.siliciumx$lastListRefreshMs = 0L;
		if (this.siliciumx$nameCache != null) {
			this.siliciumx$nameCache.clear();
		}
	}
}
