package dev.siliciumx.mixin.memory;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zero-reflection accessor for immutable {@code BlockBehaviour$BlockStateBase$Cache} fields.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
public interface BlockStateCacheAccessor {
	@Accessor("collisionShape")
	VoxelShape siliciumx$getCollisionShape();

	@Accessor("largeCollisionShape")
	boolean siliciumx$isLargeCollisionShape();

	@Accessor("isCollisionShapeFullBlock")
	boolean siliciumx$isCollisionShapeFullBlock();

	@Accessor("faceSturdy")
	boolean[] siliciumx$getFaceSturdy();
}
