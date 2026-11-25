package dev.silicium.mixin.memory;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zero-reflection accessor for immutable {@code BlockBehaviour$BlockStateBase$Cache} fields.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
public interface BlockStateCacheAccessor {
	@Accessor("collisionShape")
	VoxelShape silicium$getCollisionShape();

	@Accessor("largeCollisionShape")
	boolean silicium$isLargeCollisionShape();

	@Accessor("isCollisionShapeFullBlock")
	boolean silicium$isCollisionShapeFullBlock();

	@Accessor("faceSturdy")
	boolean[] silicium$getFaceSturdy();
}
