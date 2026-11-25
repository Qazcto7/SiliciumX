package dev.siliciumx.mixin.chunk;

import com.mojang.datafixers.DataFixer;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.FastLongCache;
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
	private FastLongCache<LevelChunk> siliciumx$fullChunkCache;

	@Inject(
		method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/chunk/ChunkGenerator;IIZLnet/minecraft/world/level/entity/ChunkStatusUpdateListener;)V",
		at = @At("RETURN")
	)
	private void siliciumx$initFullChunkCache(
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
		this.siliciumx$getOrCreateCache();
	}

	@Unique
	private FastLongCache<LevelChunk> siliciumx$getOrCreateCache() {
		FastLongCache<LevelChunk> cache = this.siliciumx$fullChunkCache;
		if (cache == null) {
			cache = new FastLongCache<>(SiliciumXConfig.get().chunkLookupCacheSize);
			this.siliciumx$fullChunkCache = cache;
		}
		return cache;
	}

	@Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
	private void siliciumx$fastGetChunkNowHead(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
		if (Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk cached = this.siliciumx$getOrCreateCache().get(ChunkPos.pack(chunkX, chunkZ));
		if (cached != null) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.CHUNK_CACHE_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"))
	private void siliciumx$fastGetChunkNowReturn(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
		if (Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk chunk = cir.getReturnValue();
		if (chunk != null) {
			this.siliciumx$getOrCreateCache().put(ChunkPos.pack(chunkX, chunkZ), chunk);
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.CHUNK_CACHE_MISSES);
		}
	}

	@Inject(
		method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void siliciumx$fastGetChunkHead(
		int chunkX,
		int chunkZ,
		ChunkStatus status,
		boolean load,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		if (status != ChunkStatus.FULL || Thread.currentThread() != this.mainThread) {
			return;
		}
		LevelChunk cached = this.siliciumx$getOrCreateCache().get(ChunkPos.pack(chunkX, chunkZ));
		if (cached != null) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.CHUNK_CACHE_HITS);
			cir.setReturnValue(cached);
		}
	}

	@Inject(
		method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("RETURN")
	)
	private void siliciumx$fastGetChunkReturn(
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
			this.siliciumx$getOrCreateCache().put(ChunkPos.pack(chunkX, chunkZ), levelChunk);
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.CHUNK_CACHE_MISSES);
		}
	}

	@Inject(method = "clearCache()V", at = @At("HEAD"))
	private void siliciumx$clearFullChunkCache(CallbackInfo ci) {
		if (this.siliciumx$fullChunkCache != null) {
			this.siliciumx$fullChunkCache.clear();
		}
	}
}
