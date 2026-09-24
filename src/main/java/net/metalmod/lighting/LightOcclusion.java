package net.metalmod.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Whether a dynamic source can illuminate anything at all.
 *
 * <p>The dynamic contribution is unshadowed: it is evaluated per fragment from a distance falloff, so
 * a light behind a wall still reaches the terrain in front of it. Vanilla does not work that way -
 * block light propagates through the world and is stopped by opaque blocks - which is why a glow
 * squid sealed inside wool lights the room in MetalMod and nothing in the game.
 *
 * <p>Full occlusion needs shadow maps and its own design, but the worst case is not a wall at all: it
 * is a source that is <em>buried</em>. A source inside opaque material, or in a cell with no opening,
 * cannot emit anything to the outside even in vanilla, because the light has no first step to take.
 * That case is decidable from a handful of block reads, so it is decided here and the source is
 * dropped outright.
 *
 * <h2>What this is not</h2>
 *
 * <p>This is not occlusion. A source in an open room still lights the far side of the wall behind it,
 * and a source in a large sealed cavity still escapes, because neither is decidable one cell at a
 * time. Those remain the documented limitation of an unshadowed evaluator; what this removes is the
 * case that looks most like a plain bug - a light that is visibly encased and still glowing.
 */
public final class LightOcclusion {

    /**
     * Light levels run 0..15, so a dampening of 15 is a block that stops light completely.
     *
     * <p>Vanilla's light engine subtracts this value while propagating, which makes it the right
     * predicate: water damps by 1 and still passes light, glass by 0 and passes it unchanged, and
     * stone, dirt or wool by 15 and passes none. Keying on "solid" instead would call water opaque and
     * put out a glow squid that is swimming in the open.
     */
    private static final int OPAQUE_DAMPENING = 15;

    private LightOcclusion() {}

    /** Whether a source at this world position is buried or sealed, and so cannot light anything. */
    public static boolean isSealed(BlockGetter level, double x, double y, double z) {
        BlockPos origin = BlockPos.containing(x, y, z);
        return isSealedAt(origin, position -> opaque(level.getBlockState(position)));
    }

    private static boolean opaque(BlockState state) {
        return state.getLightDampening() >= OPAQUE_DAMPENING;
    }

    /**
     * The decision itself, against a predicate, so it can be tested without a world.
     *
     * <p>A source is dropped when its own cell is opaque (it is inside the material) or when all six
     * neighbours are (it is in a sealed single cell). Anything with one open side keeps its light:
     * that side is somewhere the light can actually go.
     */
    static boolean isSealedAt(BlockPos origin, Predicate<BlockPos> opaqueAt) {
        if (opaqueAt.test(origin)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (!opaqueAt.test(origin.relative(direction))) {
                return false;
            }
        }
        return true;
    }
}
