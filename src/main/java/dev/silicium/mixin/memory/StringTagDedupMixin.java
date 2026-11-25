package dev.silicium.mixin.memory;

import dev.silicium.memory.StringTagPool;
import net.minecraft.nbt.StringTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Canonicalizes short immutable {@link StringTag} instances via {@link StringTagPool}, eliminating
 * tens of thousands of duplicate NBT string tags across chunk palettes, entities, items, and packets.
 */
@Mixin(StringTag.class)
public abstract class StringTagDedupMixin {
	@Inject(method = "valueOf(Ljava/lang/String;)Lnet/minecraft/nbt/StringTag;", at = @At("HEAD"), cancellable = true)
	private static void silicium$deduplicateStringTag(String value, CallbackInfoReturnable<StringTag> cir) {
		StringTag canonical = StringTagPool.internOrCreate(value);
		if (canonical != null) {
			cir.setReturnValue(canonical);
		}
	}
}
