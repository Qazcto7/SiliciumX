package dev.silicium.client.mixin.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zero-reflection accessor for protected {@link Particle} position and level fields.
 */
@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("level")
	ClientLevel silicium$getLevel();

	@Accessor("x")
	double silicium$getX();

	@Accessor("y")
	double silicium$getY();

	@Accessor("z")
	double silicium$getZ();
}
