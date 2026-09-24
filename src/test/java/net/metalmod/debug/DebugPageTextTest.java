package net.metalmod.debug;

import net.metalmod.Diagnostics;
import net.metalmod.lighting.EnvironmentRecord;

/**
 * The F3 page has no room to spare, so its text is bounded here rather than trusted to stay short.
 *
 * <p>It grew one clause at a time - a version suffix, a namespace prefix, a parenthetical explaining
 * numbers that were zero - until the MetalMod section was the longest thing on screen. These checks
 * are the thing that would have caught that.
 */
public final class DebugPageTextTest {

    /** Longest the environment summary may be. It is printed after an F3 prefix. */
    private static final int ENVIRONMENT_BUDGET = 48;

    public static int runTests() {
        try {
            environmentSummaryStaysShort();
            missingHooksNameOnlyWhatIsMissing();
            extractionBreakdownNamesTheLargestPart();
            System.out.println("PASS debug page text: environment summary and hook line stay short");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    /**
     * The extraction breakdown has to name the part that actually dominates, including the remainder.
     *
     * <p>Each case gives a different half the milliseconds, so a label hard-wired to one of them - which
     * is the plausible mistake, and the one that would send the next investigation to the wrong
     * subsystem - fails instead of reading sensibly.
     */
    private static void extractionBreakdownNamesTheLargestPart() {
        // 7.4 ms of which almost all is the entity query: the observed slow case.
        String entity = MetalModDebugEntry.extractBreakdown(7_400_000L, 7_300_000L, 40_000L);
        require(entity.contains("ent 7.3"), "the entity query is named when it dominates: " + entity);
        String index = MetalModDebugEntry.extractBreakdown(7_400_000L, 40_000L, 7_300_000L);
        require(index.contains("idx 7.3"), "the block index is named when it dominates: " + index);
        // Neither measured half dominates, so the remainder is named rather than the larger half
        // absorbing it: occlusion, sorting and publishing are a different fix again.
        String other = MetalModDebugEntry.extractBreakdown(7_400_000L, 40_000L, 30_000L);
        require(other.contains("oth 7.3"), "the remainder is named when neither half dominates: " + other);
        // Below a tenth of a millisecond there is nothing to split, and the page keeps its width.
        require(MetalModDebugEntry.extractBreakdown(99_999L, 90_000L, 0L).isEmpty(),
                "a sub-0.1 ms extraction is not split on the page");
        // Extraction faster than its parts would be a measurement bug; it must not print a negative.
        String over = MetalModDebugEntry.extractBreakdown(5_000_000L, 3_000_000L, 3_000_000L);
        require(!over.contains("-"), "an over-counted split never prints a negative: " + over);
        require(over.contains("ent 3.0"), "an over-counted split still names a measured part: " + over);
    }

    private static void environmentSummaryStaysShort() {
        EnvironmentRecord day = new EnvironmentRecord(EnvironmentRecord.VERSION, "minecraft:overworld",
                0, 256, true, false, 0f, 251097L, 0.96f, 0, 0f, 0f);
        String summary = day.summary();
        require(summary.length() <= ENVIRONMENT_BUDGET,
                "the environment summary is " + summary.length() + " chars: " + summary);
        require(!summary.contains("minecraft:"), "the namespace prefix is dropped: " + summary);
        require(!summary.contains(" v" + EnvironmentRecord.VERSION),
                "the ABI version is not repeated on the page: " + summary);
        require(summary.contains("overworld") && summary.contains("day 0.96"),
                "the dimension and time of day survive the shortening: " + summary);

        // A flag that is false is the interesting case, so it is the one that gets named.
        EnvironmentRecord nether = new EnvironmentRecord(EnvironmentRecord.VERSION, "minecraft:the_nether",
                0, 256, false, true, 0.1f, 1000L, 0.5f, 11, 0f, 0f);
        require(nether.summary().contains("no sky"),
                "a dimension without sky light says so: " + nether.summary());
        require(day.summary().length() < nether.summary().length(),
                "the flagged case is longer than the normal one");
    }

    private static void missingHooksNameOnlyWhatIsMissing() {
        // The line used to list all seven hooks every frame, which is the good case paying the width
        // cost of the bad one. Only an unreported hook may appear.
        Diagnostics.hook("LevelExtractor.extract");
        String missing = Diagnostics.missing();
        require(!missing.contains("LevelExtractor.extract"),
                "a hook that reported is not listed as missing: " + missing);
        require(!missing.contains("+") && !missing.contains("-"),
                "the list carries names, not the +/- checklist: " + missing);
        // The two screen hooks report when their screen is opened, which a session may never do. They
        // are therefore not part of what this reports on: naming them would put a permanent red
        // "missing" on the page meaning "you have not opened the settings yet", which is how a real
        // missing hook gets ignored.
        require(!missing.contains("OptionsScreen.init"),
                "a hook that waits for its screen is not reported missing: " + missing);
        require(!missing.contains("MetalModLightingConfigScreen.init"),
                "the Lighting page hook is not reported missing: " + missing);
        // The line keeps its teeth: a hook that runs on any session that draws is still named, and
        // naming one here is the whole reason the line exists.
        require(missing.contains("GameRenderer.render"),
                "a hook that should have run by now is still named: " + missing);
        for (String hook : missing.split(" ")) {
            require(hook.isEmpty() || hook.startsWith("GameRenderer") || hook.startsWith("Window")
                            || hook.startsWith("LevelExtractor") || hook.startsWith("OptionsScreen")
                            || hook.startsWith("MetalModLightingConfigScreen"),
                    "only real hook names appear: " + hook);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
