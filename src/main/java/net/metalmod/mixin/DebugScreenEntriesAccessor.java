package net.metalmod.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Part 2 of F3 registration: access to {@code DebugScreenEntries.ENTRIES_BY_ID}.
 *
 * The real {@code register(Identifier, DebugScreenEntry)} is {@code private static} and does
 * nothing but {@code ENTRIES_BY_ID.put(...)}, so reaching the map directly is equivalent and is
 * what Sodium does.
 *
 * Mixin rewrites the body of this static interface method (verified: Sodium's equivalent compiles
 * to {@code invokestatic DebugScreenEntriesAccessor.sodium$getEntries()}), so the
 * {@code AssertionError} below never runs.
 */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesAccessor {

    @Accessor("ENTRIES_BY_ID")
    static Map<Identifier, DebugScreenEntry> metalmod$entries() {
        throw new AssertionError("Mixin failed to replace this accessor");
    }
}
