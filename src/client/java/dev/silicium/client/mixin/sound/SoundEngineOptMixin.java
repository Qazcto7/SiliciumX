package dev.silicium.client.mixin.sound;

import com.mojang.blaze3d.audio.Listener;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.TickBurstCounter;
import net.minecraft.client.Options;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents OpenAL channel saturation and render-thread {@code CompletableFuture.join()} stalls
 * inside {@link SoundEngine#play(SoundInstance)} by:
 * <ol>
 *   <li>Skipping inaudible one-shot sounds strictly beyond their OpenAL attenuation radius (+2 block
 *       safety margin) right before {@code ChannelAccess.createHandle} blocks the client thread.</li>
 *   <li>Limiting identical rapid-fire sound spam within the same client tick (e.g. XP orb farms,
 *       chicken coops, piston walls) while leaving music, UI, jukeboxes, streaming, and looping
 *       sounds completely untouched.</li>
 * </ol>
 *
 * <p>Injects after Vanilla's subtitle listener notification so accessibility subtitles remain
 * unaffected.</p>
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineOptMixin {
	@Shadow
	@Final
	private Listener listener;

	@Shadow
	private int tickCount;

	@Unique
	private TickBurstCounter<Identifier> silicium$burstCounter;

	@Inject(
		method = "<init>(Lnet/minecraft/client/sounds/SoundManager;Lnet/minecraft/client/Options;Lnet/minecraft/server/packs/resources/ResourceProvider;)V",
		at = @At("RETURN")
	)
	private void silicium$initBurstCounter(
		SoundManager soundManager,
		Options options,
		ResourceProvider resourceProvider,
		CallbackInfo ci
	) {
		this.silicium$getOrCreateBurstCounter();
	}

	@Unique
	private TickBurstCounter<Identifier> silicium$getOrCreateBurstCounter() {
		TickBurstCounter<Identifier> counter = this.silicium$burstCounter;
		if (counter == null) {
			counter = new TickBurstCounter<>(64);
			this.silicium$burstCounter = counter;
		}
		return counter;
	}

	@Inject(
		method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"
		),
		cancellable = true
	)
	private void silicium$cullInaudibleOrSpamSound(
		SoundInstance sound,
		CallbackInfoReturnable<SoundEngine.PlayResult> cir
	) {
		if (sound.isLooping() || sound.isRelative() || sound.canStartSilent()) {
			return;
		}
		SoundSource source = sound.getSource();
		if (source == SoundSource.MUSIC
			|| source == SoundSource.RECORDS
			|| source == SoundSource.UI
			|| source == SoundSource.VOICE
		) {
			return;
		}
		Sound resolved = sound.getSound();
		if (resolved == null || resolved.shouldStream()) {
			return;
		}

		// 1. Early attenuation-distance cutoff before blocking on OpenAL channel allocation
		if (sound.getAttenuation() == SoundInstance.Attenuation.LINEAR) {
			double maxDist = resolved.getAttenuationDistance(sound.getVolume()) + 2.0;
			Vec3 listenerPos = this.listener.getTransform().position();
			double dx = sound.getX() - listenerPos.x();
			double dy = sound.getY() - listenerPos.y();
			double dz = sound.getZ() - listenerPos.z();
			if ((dx * dx + dy * dy + dz * dz) > (maxDist * maxDist)) {
				SiliciumMetrics.increment(SiliciumMetrics.Counter.SOUNDS_CULLED);
				cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
				return;
			}
		}

		// 2. Per-tick identical sound spam cap (prevents XP farm / piston / mob audio hitches)
		int maxPerTick = SiliciumConfig.get().maxIdenticalSoundsPerTick;
		if (maxPerTick > 0
			&& !this.silicium$getOrCreateBurstCounter().tryAcquire(this.tickCount, sound.getIdentifier(), maxPerTick)
		) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.SOUNDS_CULLED);
			cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}

	@Inject(method = "stopAll()V", at = @At("HEAD"))
	private void silicium$clearBurstOnStopAll(CallbackInfo ci) {
		if (this.silicium$burstCounter != null) {
			this.silicium$burstCounter.clear();
		}
	}
}
