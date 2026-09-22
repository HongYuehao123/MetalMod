package net.metalmod.mixin;

import net.metalmod.debug.MetalModDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Part 3 of F3 registration: make the entry visible.
 *
 * {@code DebugScreenEntryList.getStatus(id)} is {@code allStatuses.getOrDefault(id, NEVER)}, so a
 * newly registered entry is hidden unless it is given a status. {@code rebuildCurrentList()} is
 * where the enabled set is computed, and {@code loadProfile()} calls
 * {@code resetToProfile()} (which clears the status map) immediately before it - so setting the
 * status at the head of {@code rebuildCurrentList} both survives profile resets and re-applies on
 * every rebuild.
 *
 * {@code putIfAbsent} deliberately preserves a user's own choice: if they switch the entry off in
 * the debug options, we do not force it back on.
 */
@Mixin(DebugScreenEntryList.class)
public class DebugScreenEntryListMixin {

    @Shadow
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "rebuildCurrentList", at = @At("HEAD"))
    private void metalmod$enableDebugEntry(CallbackInfo ci) {
        allStatuses.putIfAbsent(MetalModDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
    }
}
