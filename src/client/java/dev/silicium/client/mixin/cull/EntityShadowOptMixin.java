package dev.silicium.client.mixin.cull;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes {@link EntityRenderer#extractShadow} in two complementary ways:
 * <ol>
 *   <li>Skips nearly invisible distant blob shadows beyond {@code entityShadowMaxDistance}
 *       (default 14 blocks, configurable up to Vanilla's 16-block cutoff).</li>
 *   <li>Eliminates 75%-88% of redundant {@code Level.getChunk(BlockPos)} lookups across each
 *       entity's footprint by reusing the last fetched chunk whenever {@code (x >> 4, z >> 4)}
 *       matches (100% pixel-identical).</li>
 * </ol>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowOptMixin {
	@Unique
	private ChunkAccess silicium$shadowLastChunk;
	@Unique
	private int silicium$shadowLastChunkX;
	@Unique
	private int silicium$shadowLastChunkZ;

	@Inject(
		method = "extractShadow(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/level/Level;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void silicium$earlySkipOrResetShadow(
		EntityRenderState state,
		Minecraft minecraft,
		Level level,
		CallbackInfo ci
	) {
		this.silicium$shadowLastChunk = null;
		if (state.distanceToCameraSq >= SiliciumConfig.get().entityShadowMaxDistanceSq()) {
			state.shadowPieces.clear();
			state.shadowRadius = 0.0f;
			SiliciumMetrics.increment(SiliciumMetrics.Counter.ENTITY_SHADOWS_OPTIMIZED);
			ci.cancel();
		}
	}

	@WrapOperation(
		method = "extractShadow(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/level/Level;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getChunk(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;"
		)
	)
	private ChunkAccess silicium$reuseSameChunkInShadowLoop(
		Level instance,
		BlockPos pos,
		Operation<ChunkAccess> original
	) {
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		ChunkAccess cached = this.silicium$shadowLastChunk;
		if (cached != null && cx == this.silicium$shadowLastChunkX && cz == this.silicium$shadowLastChunkZ) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.ENTITY_SHADOWS_OPTIMIZED);
			return cached;
		}
		ChunkAccess fetched = original.call(instance, pos);
		this.silicium$shadowLastChunk = fetched;
		this.silicium$shadowLastChunkX = cx;
		this.silicium$shadowLastChunkZ = cz;
		return fetched;
	}

	@Inject(
		method = "extractShadow(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/level/Level;)V",
		at = @At("RETURN")
	)
	private void silicium$clearShadowChunkRef(
		EntityRenderState state,
		Minecraft minecraft,
		Level level,
		CallbackInfo ci
	) {
		this.silicium$shadowLastChunk = null;
	}
}
