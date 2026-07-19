package net.explorersfriend.mixin;

import net.explorersfriend.world.BlockChangeHooks;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The only mixin in this mod.
 *
 * <p>Justification: Fabric API offers no universal "block changed" event. Player
 * break/place callbacks miss explosions, fluid flow, pistons, mob griefing, world
 * generation touch-ups, and command edits. {@code WorldChunk#setBlockState} is the
 * single funnel every one of those goes through, so a tail injection here gives a
 * complete, cheap dirty signal. The handler only records a chunk position in a
 * concurrent set (no allocation beyond the boxed key, no IO, no locks).</p>
 */
@Mixin(WorldChunk.class)
abstract class WorldChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void explorersfriend$afterBlockChange(BlockPos pos, BlockState state, boolean moved,
                                                  CallbackInfoReturnable<BlockState> cir) {
        // A null return value means the state did not actually change.
        if (cir.getReturnValue() == null) {
            return;
        }
        WorldChunk self = (WorldChunk) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isClient()) {
            return;
        }
        BlockChangeHooks.onBlockChanged(world, pos, state);
    }
}
