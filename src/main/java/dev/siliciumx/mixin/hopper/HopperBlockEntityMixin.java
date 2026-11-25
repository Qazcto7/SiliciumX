package dev.siliciumx.mixin.hopper;

import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla-faithful hopper tick micro-optimizations:
 * <ul>
 *   <li>Replaces iterator allocation in {@code inventoryFull()} with a direct indexed loop.</li>
 *   <li>Short-circuits {@code suckInItems} slot scanning when the source container above is empty,
 *       avoiding 27-54 redundant {@code tryTakeInItemFromSlot} checks per tick on idle storage sorters.</li>
 * </ul>
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
	@Unique
	private static final int[] SILICIUMX$EMPTY_SLOTS = new int[0];

	@Shadow
	private NonNullList<ItemStack> items;

	@Shadow
	private static int[] getSlots(Container container, Direction direction) {
		throw new AssertionError();
	}

	@Inject(method = "inventoryFull()Z", at = @At("HEAD"), cancellable = true)
	private void siliciumx$fastInventoryFull(CallbackInfoReturnable<Boolean> cir) {
		NonNullList<ItemStack> list = this.items;
		for (int i = 0, len = list.size(); i < len; i++) {
			ItemStack stack = list.get(i);
			if (stack.isEmpty() || stack.getCount() != stack.getMaxStackSize()) {
				cir.setReturnValue(false);
				return;
			}
		}
		cir.setReturnValue(true);
	}

	@Redirect(
		method = "suckInItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getSlots(Lnet/minecraft/world/Container;Lnet/minecraft/core/Direction;)[I"
		)
	)
	private static int[] siliciumx$skipEmptySourceContainerScan(Container container, Direction direction) {
		if (container.isEmpty()) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.HOPPER_EMPTY_SKIPS);
			return SILICIUMX$EMPTY_SLOTS;
		}
		return getSlots(container, direction);
	}
}
