package dev.siliciumx.mixin.startup;

import com.mojang.datafixers.DSL;
import dev.siliciumx.SiliciumXConstants;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Defers eager startup pre-optimization of DataFixerUpper schema rewrite rules inside
 * {@link DataFixers#optimize(Set)}.
 *
 * <p>DataFixerUpper still compiles and caches any rewrite rules on demand if an older world or
 * chunk needs upgrading, so world migration behavior remains 100% Vanilla-identical while
 * avoiding seconds of startup CPU burn and ~40-60 MB of unused compiled rule objects.</p>
 *
 * <p>Automatically yields when ModernFix, LazyDFU, or Fastload is installed.</p>
 */
@Mixin(DataFixers.class)
public abstract class DataFixersStartupMixin {
	@Inject(
		method = "optimize(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void siliciumx$deferEagerDataFixerOptimization(
		Set<DSL.TypeReference> types,
		CallbackInfoReturnable<CompletableFuture<?>> cir
	) {
		if (types == null || types.isEmpty()) {
			return;
		}
		SiliciumXMetrics.increment(SiliciumXMetrics.Counter.DATAFIXER_OPT_SKIPPED);
		SiliciumXConstants.LOGGER.info(
			"Deferred eager DataFixer bootstrap optimization ({} type references; on-demand upgrades remain active).",
			types.size()
		);
		cir.setReturnValue(CompletableFuture.completedFuture(null));
	}
}
