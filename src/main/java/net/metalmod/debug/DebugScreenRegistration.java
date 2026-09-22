package net.metalmod.debug;

import net.metalmod.mixin.DebugScreenEntriesAccessor;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Registers {@link MetalModDebugEntry} with Minecraft's debug screen registry.
 *
 * <h2>Why this needs three separate pieces</h2>
 * Adding an F3 section in Minecraft 26.2 is not a single call. Sodium does the same three things:
 *
 * <ol>
 *   <li>A {@link DebugScreenEntry} implementation - {@link MetalModDebugEntry}.</li>
 *   <li><b>Registration.</b> {@code DebugScreenEntries.register(...)} is <em>private static</em>, so
 *       the entry is put into the private {@code ENTRIES_BY_ID} map through the
 *       {@link DebugScreenEntriesAccessor} mixin.</li>
 *   <li><b>Visibility.</b> {@code DebugScreenEntryList.getStatus(id)} is
 *       {@code getOrDefault(id, NEVER)} - a registered entry that is not in the status map is
 *       <em>hidden</em>. {@code DebugScreenEntryListMixin} puts our id in with
 *       {@code IN_OVERLAY}. Skipping this step is the classic "I registered it and nothing
 *       appears" bug (NeoForge shipped a dedicated fix for it: "modded debug entries not being
 *       visible upon initial game load").</li>
 * </ol>
 */
public final class DebugScreenRegistration {

    private static volatile boolean registered = false;
    private static volatile long displayCalls = 0L;

    private DebugScreenRegistration() {
    }

    /**
     * Register the F3 entry. Safe to call more than once.
     *
     * @return true if the entry is present in the registry afterwards.
     */
    public static boolean register() {
        if (registered) {
            return true;
        }
        try {
            Map<Identifier, DebugScreenEntry> entries = DebugScreenEntriesAccessor.metalmod$entries();
            if (entries == null) {
                System.err.println("[MetalMod] F3 registration failed: the DebugScreenEntries "
                        + "accessor mixin returned null, so it did not apply.");
                return false;
            }
            entries.put(MetalModDebugEntry.ID, new MetalModDebugEntry());
            registered = true;
            return true;
        } catch (Throwable t) {
            System.err.println("[MetalMod] F3 registration failed (accessor mixin missing or the "
                    + "registry layout changed): " + t);
            return false;
        }
    }

    /**
     * Positively confirm the entry is really in the registry.
     *
     * This is a verification the mod can do itself, instead of hoping an injection applied: it
     * reads back through Minecraft's own public API. A silent mixin failure shows up here as
     * "not registered", which is far easier to diagnose than absent F3 lines.
     */
    public static boolean verify() {
        try {
            return DebugScreenEntries.getEntry(MetalModDebugEntry.ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void recordDisplayCall() {
        displayCalls++;
    }

    /** How many times the F3 section has been built; 0 means the entry is still hidden or F3 was never opened. */
    public static long displayCallCount() {
        return displayCalls;
    }
}
