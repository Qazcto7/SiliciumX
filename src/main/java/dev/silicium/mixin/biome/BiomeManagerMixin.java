package dev.silicium.mixin.biome;

import dev.silicium.util.FastBiomeVoronoi;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Accelerates 3D Voronoi biome resolution with exact mathematical lower-bound corner pruning
 * (100% bit-for-bit identical results to Vanilla).
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerMixin {
	@Shadow
	@Final
	private BiomeResolver noiseBiomeSource;

	@Shadow
	@Final
	private long biomeZoomSeed;

	@Inject(method = "getBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
	private void silicium$fastGetBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
		int bestCorner = FastBiomeVoronoi.selectClosestCorner(this.biomeZoomSeed, x, y, z);
		int quartX = (x - 2) >> 2;
		int quartY = (y - 2) >> 2;
		int quartZ = (z - 2) >> 2;

		int targetX = (bestCorner & 4) == 0 ? quartX : quartX + 1;
		int targetY = (bestCorner & 2) == 0 ? quartY : quartY + 1;
		int targetZ = (bestCorner & 1) == 0 ? quartZ : quartZ + 1;

		cir.setReturnValue(this.noiseBiomeSource.getNoiseBiome(targetX, targetY, targetZ));
	}
}
