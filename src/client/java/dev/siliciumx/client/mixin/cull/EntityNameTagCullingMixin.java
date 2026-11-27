package dev.siliciumx.client.mixin.cull;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.siliciumx.config.SiliciumXConfig;
import dev.siliciumx.metrics.SiliciumXMetrics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Distance-culls overhead entity nametags beyond {@code nameTagMaxDistance} while preserving the
 * entity currently under the player's crosshair and leaving {@link EntityRenderState#distanceToCameraSq}
 * intact for downstream render layers.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagCullingMixin<T extends Entity, S extends EntityRenderState> {
	@Shadow
	@Final
	protected EntityRenderDispatcher entityRenderDispatcher;

	@WrapOperation(
		method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z"
		)
	)
	private boolean siliciumx$cullDistantNameTag(
		EntityRenderer<T, S> instance,
		T entity,
		double distanceToCameraSq,
		Operation<Boolean> original
	) {
		if (entity != this.entityRenderDispatcher.crosshairPickEntity
			&& distanceToCameraSq > SiliciumXConfig.get().nameTagMaxDistanceSq()
		) {
			if (entity instanceof Player || entity.hasCustomName()) {
				SiliciumXMetrics.increment(SiliciumXMetrics.Counter.NAMETAGS_CULLED);
			}
			return false;
		}
		return original.call(instance, entity, distanceToCameraSq);
	}
}
