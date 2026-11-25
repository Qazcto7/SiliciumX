package dev.silicium.mixin.chunk;

import com.mojang.datafixers.DataFixer;
import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import dev.silicium.util.FastLongCache;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

/**
 * Adds a zero-allocation, fixed-capacity 2-way associative cache (default 128 slots) for
 * {@link ChunkStatus#FULL} chunk lookups on the server main thread, supplementing Vanilla's
 * 4-entry linear array that thrashes whenever entities/blocks span more than 4 chunks.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	private Thread mainThread;

	@Unique
	private FastLongCache<LevelChunk> silicium$fullChunkCache;

	@Inject(
		method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/chunk/ChunkGenerator;IIZLnet/minecraft/world/level/entity/ChunkStatusUpdateListener;)V",
		at = @At("RETURN")
	)
	private void silicium$initFullChunkCache(
		ServerLevel level,
		LevelStorageSource.LevelStorageAccess levelStorageAccess,
		DataFixer fixerUpper,
		StructureTemplateManager structureTemplateManager,
		Executor executor,
		ChunkGenerator generator,
		int viewDistance,
		int simulationDistance,
		boolean sync,
		ChunkStatusUpdateListener chunkStatusListener,
		CallbackInfo ci
	) {
		this.silicium$getOrCreateCache();
	}

	@Unique
	private FastLongCache<LevelChunk> silicium$getOrCreateCache() {
		FastLongCache<LevelChunk> cache = this.silicium$fullChunkCache;
		if (cache == null) {
			cache = new FastLongCache<>(SiliciumConfig.get().chunkLookupCacheSize);
			this.silicium$fullChunkCache = cache;
		}
		return cache;
	}

	@Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
	private void silicium$fastGetChunkNowHead(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
		if (Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk cached = this.silicium$getOrCreateCache().get(ChunkPos.pack(chunkX, chunkZ));
		if (cached != null) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.CHUNK_CACHE_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"))
	private void silicium$fastGetChunkNowReturn(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
		if (Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk chunk = cir.getReturnValue();
		if (chunk != null) {
			this.silicium$getOrCreateCache().put(ChunkPos.pack(chunkX, chunkZ), chunk);
			SiliciumMetrics.increment(SiliciumMetrics.Counter.CHUNK_CACHE_MISSES);
		}
	}

	@Inject(
		method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void silicium$fastGetChunkHead(
		int chunkX,
		int chunkZ,
		ChunkStatus status,
		boolean load,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		if (status != ChunkStatus.FULL || Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk cached = this.silicium$getOrCreateCache().get(ChunkPos.pack(chunkX, chunkZ));
		if (cached != null) {
			SiliciumMetrics.increment(SiliciumMetrics.Counter.CHUNK_CACHE_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(
		method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("RETURN")
	)
	private void silicium$fastGetChunkReturn(
		int chunkX,
		int chunkZ,
		ChunkStatus status,
		boolean load,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		if (status != ChunkStatus.FULL || Thread.currentThread() != this.mainThread) {
			return;
		}
		ChunkAccess result = cir.getReturnValue();
		if (result instanceof LevelChunk levelChunk) {
			this.silicium$getOrCreateCache().put(ChunkPos.pack(chunkX, chunkZ), levelChunk);
			SiliciumMetrics.increment(SiliciumMetrics.Counter.CHUNK_CACHE_MISSES);
		}
	}

	@Inject(method = "clearCache()V", at = @At("HEAD"))
	private void silicium$clearFullChunkCache(CallbackInfo ci) {
		if (this.silicium$fullChunkCache != null) {
			this.silicium$fullChunkCache.clear();
		}
	}
}
