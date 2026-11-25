package dev.siliciumx.client.mixin.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eliminates 1,334 redundant {@code ClientLevel.getFluidState(BlockPos)} chunk/section lookups per
 * client tick (26,680/sec) inside {@code ClientLevel.doAnimateTick} by reusing the {@link BlockState}
 * already fetched two instructions earlier for the exact same block coordinate.
 */
@Mixin(ClientLevel.class)
public abstract class ClientAnimateTickMixin {
	@Unique
	private BlockState siliciumx$capturedAnimateBlockState;
	@Unique
	private long siliciumx$capturedPosLong;

	@WrapOperation(
		method = "doAnimateTick(IIIILnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
			ordinal = 0
		)
	)
	private BlockState siliciumx$captureBlockState(ClientLevel instance, BlockPos pos, Operation<BlockState> original) {
		BlockState state = original.call(instance, pos);
		this.siliciumx$capturedAnimateBlockState = state;
		this.siliciumx$capturedPosLong = pos.asLong();
		return state;
	}

	@WrapOperation(
		method = "doAnimateTick(IIIILnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
		)
	)
	private FluidState siliciumx$reuseFluidStateFromBlockState(
		ClientLevel instance,
		BlockPos pos,
		Operation<FluidState> original
	) {
		BlockState captured = this.siliciumx$capturedAnimateBlockState;
		this.siliciumx$capturedAnimateBlockState = null;
		if (captured != null && pos.asLong() == this.siliciumx$capturedPosLong) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ANIMATE_FLUID_LOOKUPS_SAVED);
			return captured.getFluidState();
		}
		return original.call(instance, pos);
	}
}
