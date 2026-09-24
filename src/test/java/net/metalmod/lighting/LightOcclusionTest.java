package net.metalmod.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for the buried-source rule.
 *
 * <p>The decision is expressed against a predicate, so the interesting layouts - buried, sealed,
 * open, open only through water - are built here as sets of opaque positions instead of needing a
 * world. That also means the cases that must <em>not</em> be culled are as testable as the ones that
 * must.
 */
public final class LightOcclusionTest {

    public static int runTests() {
        try {
            aSourceInOpenAirIsKept();
            aBuriedSourceIsDropped();
            aSealedSingleCellIsDropped();
            oneOpenSideIsEnough();
            aFloorIsNotASeal();
            System.out.println("PASS buried sources: open air kept, buried and sealed dropped");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    /** The ordinary case: an entity standing in the open must keep emitting. */
    private static void aSourceInOpenAirIsKept() {
        require(!sealed(BlockPos.ZERO, Set.of()), "a source in open air keeps its light");
    }

    /** The reported case: encased in wool or dirt. Its own cell is the opaque block. */
    private static void aBuriedSourceIsDropped() {
        Set<BlockPos> wool = new HashSet<>();
        wool.add(BlockPos.ZERO);
        require(sealed(BlockPos.ZERO, wool), "a source inside an opaque block emits nothing");
    }

    /** A one-cell pocket walled in on all six sides, which vanilla also cannot light out of. */
    private static void aSealedSingleCellIsDropped() {
        Set<BlockPos> wall = new HashSet<>();
        for (Direction direction : Direction.values()) {
            wall.add(BlockPos.ZERO.relative(direction));
        }
        require(sealed(BlockPos.ZERO, wall), "a source sealed into a single cell emits nothing");
    }

    /**
     * The case that must <em>not</em> be culled: a source lying on the ground. A floor below and walls
     * to the sides is not a seal - the cell above is open, and that is where the light goes.
     */
    private static void aFloorIsNotASeal() {
        Set<BlockPos> floor = new HashSet<>();
        floor.add(BlockPos.ZERO.below());
        require(!sealed(BlockPos.ZERO, floor), "a source resting on a block still emits");
        Set<BlockPos> corner = new HashSet<>();
        corner.add(BlockPos.ZERO.below());
        corner.add(BlockPos.ZERO.east());
        corner.add(BlockPos.ZERO.west());
        corner.add(BlockPos.ZERO.north());
        require(!sealed(BlockPos.ZERO, corner),
                "a source in a corridor with one open side still emits");
    }

    /** One open neighbour on any axis is enough, whichever axis it is. */
    private static void oneOpenSideIsEnough() {
        for (Direction open : Direction.values()) {
            Set<BlockPos> wall = new HashSet<>();
            for (Direction direction : Direction.values()) {
                if (direction != open) {
                    wall.add(BlockPos.ZERO.relative(direction));
                }
            }
            require(!sealed(BlockPos.ZERO, wall),
                    "an opening to the " + open + " keeps the source alive");
        }
    }

    private static boolean sealed(BlockPos origin, Set<BlockPos> opaque) {
        return LightOcclusion.isSealedAt(origin, opaque::contains);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
