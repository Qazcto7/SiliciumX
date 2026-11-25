package dev.silicium.client.mixin.cull;

import dev.silicium.config.SiliciumConfig;
import dev.silicium.metrics.SiliciumMetrics;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Eliminates wasted sign text rendering passes inside {@link AbstractSignRenderer#extractRenderState}:
 * <ul>
 *   <li>Skips completely blank sign faces (zero visual difference, saves 4 glyph/width calls per empty face).</li>
 *   <li>Skips back-facing sign text using the exact {@link SignBlock} normal with a conservative
 *       0.12-block thickness margin so grazing angles never clip.</li>
 *   <li>Skips unreadable sign text beyond {@code signTextMaxRenderDistance} (default 32 blocks).</li>
 * </ul>
 */
@Mixin(AbstractSignRenderer.class)
public abstract class SignTextCullingMixin {
	@Unique
	private static final double SILICIUM$BACKFACE_MARGIN = 0.12;

	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
		at = @At("TAIL")
	)
	private void silicium$cullRedundantSignText(
		SignBlockEntity blockEntity,
		SignRenderState state,
		float partialTick,
		Vec3 cameraPos,
		ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
		CallbackInfo ci
	) {
		boolean filtered = state.isTextFilteringEnabled;

		// 1. Blank text fast-drop (100% visually identical)
		if (state.frontText != null && silicium$isSignTextBlank(state.frontText, filtered)) {
			state.frontText = null;
			SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
		}
		if (state.backText != null && silicium$isSignTextBlank(state.backText, filtered)) {
			state.backText = null;
			SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
		}
		if (state.frontText == null && state.backText == null) {
			return;
		}
		if (cameraPos == null) {
			return;
		}

		// 2. Distance culling for tiny unreadable sign text
		BlockPos pos = blockEntity.getBlockPos();
		double dxCenter = (pos.getX() + 0.5) - cameraPos.x();
		double dyCenter = (pos.getY() + 0.5) - cameraPos.y();
		double dzCenter = (pos.getZ() + 0.5) - cameraPos.z();
		if ((dxCenter * dxCenter + dyCenter * dyCenter + dzCenter * dzCenter) > SiliciumConfig.get().signTextMaxRenderDistanceSq()) {
			if (state.frontText != null) {
				state.frontText = null;
				SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
			}
			if (state.backText != null) {
				state.backText = null;
				SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
			}
			return;
		}

		// 3. Exact back-face culling matching SignBlockEntity.getSlotPlayerIsFacing geometry
		BlockState blockState = blockEntity.getBlockState();
		if (blockState.getBlock() instanceof SignBlock signBlock) {
			Vec3 center = signBlock.getSignHitboxCenterPosition(blockState);
			double dx = cameraPos.x() - (pos.getX() + center.x());
			double dz = cameraPos.z() - (pos.getZ() + center.z());
			float rad = signBlock.getYRotationDegrees(blockState) * Mth.DEG_TO_RAD;
			double dot = dx * (-Mth.sin(rad)) + dz * Mth.cos(rad);

			if (dot > SILICIUM$BACKFACE_MARGIN && state.backText != null) {
				state.backText = null;
				SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
			} else if (dot < -SILICIUM$BACKFACE_MARGIN && state.frontText != null) {
				state.frontText = null;
				SiliciumMetrics.increment(SiliciumMetrics.Counter.SIGN_TEXT_PASSES_SKIPPED);
			}
		}
	}

	@Unique
	private static boolean silicium$isSignTextBlank(SignText text, boolean filtered) {
		if (text == SignText.EMPTY) {
			return true;
		}
		List<Component> messages = text.getMessages(filtered);
		for (int i = 0, size = messages.size(); i < size; i++) {
			Component line = messages.get(i);
			if (!line.getSiblings().isEmpty()) {
				return false;
			}
			ComponentContents contents = line.getContents();
			if (contents == PlainTextContents.EMPTY) {
				continue;
			}
			if (contents instanceof PlainTextContents plain) {
				if (!plain.text().isEmpty()) {
					return false;
				}
			} else {
				return false;
			}
		}
		return true;
	}
}
