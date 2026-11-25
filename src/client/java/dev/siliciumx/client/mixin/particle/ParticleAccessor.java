package dev.siliciumx.client.mixin.particle;

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
	ClientLevel siliciumx$getLevel();

	@Accessor("x")
	double siliciumx$getX();

	@Accessor("y")
	double siliciumx$getY();

	@Accessor("z")
	double siliciumx$getZ();
}
