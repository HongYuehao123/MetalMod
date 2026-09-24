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
            System.out.println("PASS debug page text: environment summary and hook line stay short");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
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
