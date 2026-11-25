package dev.silicium.mixin.worldgen;

import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Package-private accessor interface for {@link GradientNoise}'s permutation table.
 */
@Mixin(GradientNoise.class)
public interface GradientNoiseAccessor {
	@Accessor("perms")
	byte[] silicium$getPerms();
}
