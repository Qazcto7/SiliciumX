package dev.siliciumx.client.mixin.hud;

import dev.siliciumx.client.SiliciumXClientState;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side mutation epoch tracker on {@link Scoreboard} so cached scoreboard sidebar layouts
 * and player Tab-list names invalidate immediately (0 ms delay) whenever any objective, score,
 * or team changes.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardChangeTrackerMixin {
	@Inject(
		method = {
			"setDisplayObjective(Lnet/minecraft/world/scores/DisplaySlot;Lnet/minecraft/world/scores/Objective;)V",
			"removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
			"onObjectiveAdded(Lnet/minecraft/world/scores/Objective;)V",
			"onObjectiveChanged(Lnet/minecraft/world/scores/Objective;)V",
			"onObjectiveRemoved(Lnet/minecraft/world/scores/Objective;)V",
			"onScoreChanged(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Lnet/minecraft/world/scores/Score;)V",
			"onScoreLockChanged(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;)V",
			"onPlayerRemoved(Lnet/minecraft/world/scores/ScoreHolder;)V",
			"onPlayerScoreRemoved(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;)V",
			"onTeamAdded(Lnet/minecraft/world/scores/PlayerTeam;)V",
			"onTeamChanged(Lnet/minecraft/world/scores/PlayerTeam;)V",
			"onTeamRemoved(Lnet/minecraft/world/scores/PlayerTeam;)V"
		},
		at = @At("HEAD")
	)
	private void siliciumx$onScoreboardMutated(CallbackInfo ci) {
		SiliciumXClientState.bumpScoreboardEpoch();
	}

	@Inject(
		method = "addPlayerToTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)Z",
		at = @At("RETURN")
	)
	private void siliciumx$onPlayerAddedToTeam(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			SiliciumXClientState.bumpScoreboardEpoch();
		}
	}
}
