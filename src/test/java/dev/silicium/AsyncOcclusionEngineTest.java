package dev.silicium;

import dev.silicium.client.SiliciumClientState;
import dev.silicium.client.cull.AsyncOcclusionEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncOcclusionEngineTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void offloadsRaycastToWorkerMaintainsZeroPopInAndInvalidatesOnMove() throws Exception {
		// Solid 1-block thick stone wall at z = 10 covering x in [-8..8], y in [56..72]
		BlockGetter walledLevel = levelWithSolidPredicate(
			pos -> pos.getZ() == 10
				&& pos.getX() >= -8 && pos.getX() <= 8
				&& pos.getY() >= 56 && pos.getY() <= 72
		);

		AsyncOcclusionEngine engine = new AsyncOcclusionEngine("Test");
		try {
			SiliciumClientState.advanceFrame();

			// Camera at (0.5, 64.5, 0.5), target chest at (0..1, 64..65, 20..21) behind wall
			// Frame 0 MUST return false (visible) for zero pop-in while worker computes off-thread
			assertFalse(engine.isBoxOccluded(walledLevel, 42L, 0.5, 64.5, 0.5, 0.0, 64.0, 20.0, 1.0, 65.0, 21.0));

			boolean observedCulled = false;
			long deadline = System.nanoTime() + 2_000_000_000L;
			while (System.nanoTime() < deadline) {
				if (engine.isBoxOccluded(walledLevel, 42L, 0.5, 64.5, 0.5, 0.0, 64.0, 20.0, 1.0, 65.0, 21.0)) {
					observedCulled = true;
					break;
				}
				Thread.sleep(1L);
			}
			assertTrue(observedCulled, "Worker thread should mark wall-occluded box as culled");

			// Camera teleport > 0.30 blocks immediately invalidates occluded verdict -> 0 pop-in
			assertFalse(engine.isBoxOccluded(walledLevel, 42L, 1.5, 64.5, 0.5, 0.0, 64.0, 20.0, 1.0, 65.0, 21.0));

			// Open a doorway hole -> worker keeps reporting visible (false)
			BlockGetter doorwayLevel = levelWithSolidPredicate(
				pos -> pos.getZ() == 10
					&& pos.getX() >= -8 && pos.getX() <= 8
					&& pos.getY() >= 56 && pos.getY() <= 72
					&& !(pos.getX() == 0 && pos.getY() == 64)
			);
			SiliciumClientState.advanceFrame();
			for (int i = 0; i < 10; i++) {
				assertFalse(engine.isBoxOccluded(doorwayLevel, 99L, 0.5, 64.5, 0.5, 0.0, 64.0, 20.0, 1.0, 65.0, 21.0));
				Thread.sleep(2L);
			}

			// World clear resets state cleanly
			engine.clear();
			assertFalse(engine.isBoxOccluded(walledLevel, 42L, 0.5, 64.5, 0.5, 0.0, 64.0, 20.0, 1.0, 65.0, 21.0));
		} finally {
			engine.shutdown();
		}
	}

	private static BlockGetter levelWithSolidPredicate(Predicate<BlockPos> isSolid) {
		return new BlockGetter() {
			@Override
			public BlockEntity getBlockEntity(BlockPos pos) {
				return null;
			}

			@Override
			public BlockState getBlockState(BlockPos pos) {
				return isSolid.test(pos) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
			}

			@Override
			public FluidState getFluidState(BlockPos pos) {
				return Fluids.EMPTY.defaultFluidState();
			}

			@Override
			public int getHeight() {
				return 384;
			}

			@Override
			public int getMinY() {
				return -64;
			}
		};
	}
}
