package dev.siliciumx.mixin.memory;

import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.BoundedStringInterner;
import net.minecraft.nbt.NbtAccounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInput;

/**
 * Canonicalizes repeated NBT {@code CompoundTag} key names read from region files and network packets
 * using a lock-free, fixed-size bounded interner (4096 slots, max key length 48 chars).
 */
@Mixin(targets = "net.minecraft.nbt.CompoundTag$1")
public abstract class CompoundTagKeyDedupMixin {
	@Unique
	private static final BoundedStringInterner SILICIUMX$NBT_KEY_INTERNER =
		new BoundedStringInterner(4096, 48);

	@Inject(
		method = "readString(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Ljava/lang/String;",
		at = @At("RETURN"),
		cancellable = true
	)
	private static void siliciumx$deduplicateNbtKey(
		DataInput input,
		NbtAccounter accounter,
		CallbackInfoReturnable<String> cir
	) {
		String raw = cir.getReturnValue();
		String canonical = SILICIUMX$NBT_KEY_INTERNER.intern(raw);
		if (canonical != raw) {
			SiliciumXMetrics.increment(SiliciumXMetrics.Counter.NBT_KEYS_DEDUPED);
			cir.setReturnValue(canonical);
		}
	}
}
