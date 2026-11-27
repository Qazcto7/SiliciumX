package dev.siliciumx.mixin.cpu;

import dev.siliciumx.metrics.SiliciumXMetrics;
import dev.siliciumx.util.ThreadPoolTuner;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;

/**
 * Balances {@link Util} background and IO executor worker counts and priorities so background
 * workloads do not starve the Render Thread or Server Main Thread.
 *
 * <p>Automatically yields when ModernFix, C2ME, Smooth Boot, or ThreadTweak is installed, and
 * always respects an explicit {@code -Dmax.bg.threads} JVM flag.</p>
 */
@Mixin(Util.class)
public abstract class UtilThreadTuningMixin {
	@Inject(method = "maxAllowedExecutorThreads()I", at = @At("RETURN"), cancellable = true)
	private static void siliciumx$balanceBackgroundThreadCount(CallbackInfoReturnable<Integer> cir) {
		int available = Runtime.getRuntime().availableProcessors();
		boolean explicitProp = System.getProperty("max.bg.threads") != null;
		int tuned = ThreadPoolTuner.computeBackgroundThreadCount(available, cir.getReturnValueI(), explicitProp);
		if (tuned != cir.getReturnValueI()) {
			cir.setReturnValue(tuned);
		}
	}

	@ModifyArg(
		method = "makeExecutor(Ljava/lang/String;)Lnet/minecraft/TracingExecutor;",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/ForkJoinPool;<init>(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)V"
		),
		index = 1
	)
	private static ForkJoinPool.ForkJoinWorkerThreadFactory siliciumx$tuneBackgroundWorkerPriority(
		ForkJoinPool.ForkJoinWorkerThreadFactory delegate
	) {
		int priority = ThreadPoolTuner.backgroundWorkerPriority(Runtime.getRuntime().availableProcessors());
		return pool -> {
			ForkJoinWorkerThread thread = delegate.newThread(pool);
			if (thread != null) {
				thread.setPriority(priority);
				SiliciumXMetrics.increment(SiliciumXMetrics.Counter.WORKER_THREADS_TUNED);
			}
			return thread;
		};
	}

	@ModifyArg(
		method = "makeIoExecutor(Ljava/lang/String;Z)Lnet/minecraft/TracingExecutor;",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/Executors;newCachedThreadPool(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"
		),
		index = 0
	)
	private static ThreadFactory siliciumx$tuneIoWorkerPriority(ThreadFactory delegate) {
		int priority = ThreadPoolTuner.ioWorkerPriority();
		return runnable -> {
			Thread thread = delegate.newThread(runnable);
			if (thread != null) {
				thread.setPriority(priority);
				SiliciumXMetrics.increment(SiliciumXMetrics.Counter.WORKER_THREADS_TUNED);
			}
			return thread;
		};
	}
}
