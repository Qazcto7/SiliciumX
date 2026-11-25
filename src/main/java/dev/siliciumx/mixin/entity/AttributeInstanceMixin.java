package dev.siliciumx.mixin.entity;

import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Fast-paths {@link AttributeInstance#calculateValue()} when the attribute has no modifiers
 * (true for ~95% of mob/player attributes), skipping 3 EnumMap lookups and 3 iterator passes.
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeInstanceMixin {
	@Shadow
	@Final
	private Holder<Attribute> attribute;

	@Shadow
	@Final
	private Map<Identifier, AttributeModifier> modifierById;

	@Shadow
	private double baseValue;

	@Inject(method = "calculateValue()D", at = @At("HEAD"), cancellable = true)
	private void siliciumx$fastCalculateUnmodifiedAttribute(CallbackInfoReturnable<Double> cir) {
		if (this.modifierById.isEmpty()) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.ATTRIBUTE_FAST_CALCS);
			cir.setReturnValue(this.attribute.value().sanitizeValue(this.baseValue));
		}
	}
}
