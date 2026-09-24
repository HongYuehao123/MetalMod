package net.metalmod.debug;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;
import net.metalmod.memory.UnifiedMemoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * MetalMod's F3 debug screen section.
 *
 * Since Minecraft 1.21.9+ the debug screen is no longer assembled by
 * {@code DebugScreenOverlay.extractLines} (that method still exists but is dead code). It is built
 * from a registry of {@link DebugScreenEntry} objects: the overlay iterates
 * {@code DebugScreenEntryList.getCurrentlyEnabled()} and calls {@link #display} on each.
 *
 * Adding lines therefore takes three parts - see {@link DebugScreenRegistration} for parts 2 and 3.
 */
public class MetalModDebugEntry implements DebugScreenEntry {

    /** Registry id. Must be a valid namespaced Identifier or the entry will not resolve. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("metalmod", "status");

    @Override
    public void display(DebugScreenDisplayer displayer,
                        Level clientLevel,
                        LevelChunk clientChunk,
                        LevelChunk serverChunk) {
        DebugScreenRegistration.recordDisplayCall();

        // The first line has to answer "is Metal drawing this frame?". It used to report the
        // *MetalFX frame pipeline* under the bare label "Pipeline", so a session rendering happily on
        // Metal still showed `Pipeline: inactive (Vulkan interop not registered)` - the upscaler is
        // inactive by design, but nothing said so, and the line read as if the backend were off.
        // The engine's own DeviceInfo is the authoritative answer: it names the backend it selected.
        String backend = "none";
        com.mojang.blaze3d.systems.GpuDevice device =
                com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device != null && device.getDeviceInfo() != null) {
            backend = device.getDeviceInfo().backendName();
        }
        boolean metalActive = "Metal".equals(backend);
        // One line for "is it drawing, at what size, and how fast". The frame counter breakdown -
        // buffers, native calls, copies, fences - moved to the 30-second log line: it is the same
        // information, and on F3 it cost more width than every other line put together.
        String resolution = "?";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getWindow() != null) {
            resolution = minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight();
        }
        StringBuilder head = new StringBuilder("§6[MetalMod]§r ");
        head.append(metalActive ? "§aMetal§r" : "§7" + backend + "§r");
        head.append(" | §b").append(resolution);
        if (metalActive) {
            head.append("§r | §b").append(oneDecimal(net.metalmod.backend.MetalDevice.lastFrameMs()))
                    .append(" ms§r | wait §b")
                    .append(oneDecimal(net.metalmod.backend.MetalDevice.lastAcquireWaitMs()))
                    .append(" ms§r | §b").append(net.metalmod.backend.MetalDevice.lastFrameDraws())
                    .append("§r draws");
        }
        displayer.addLine(head.toString());
        // The light set is only extracted when the feature is on and the Metal backend is drawing, so
        // this line is the in-game answer to "is the extractor running, and what did it publish?".
        // The live device's switches, not the launch flags: a setting can now come from the settings
        // screen, and F3 has to report what the running frame is actually doing.
        net.metalmod.backend.MetalDevice live = net.metalmod.backend.MetalDevice.active();
        // active(), not the setting: while the game suppresses evaluation the page has to read as off,
        // because showing an on feature with nothing published would be the one place the suppression
        // announced itself.
        boolean dynamicLights = net.metalmod.lighting.LightingSettings.active();
        if (metalActive && dynamicLights) {
            var cost = net.metalmod.backend.MetalDevice.lightingStats();
            // Sub-millisecond extraction is the normal case at low light counts, and one decimal
            // rounds it to "0.0 ms" - uninformative in exactly the scaling pass this line exists for.
            double extractMs = cost.extractNanos() / 1_000_000.0;
            String extract = extractMs < 1.0
                    ? String.format(java.util.Locale.ROOT, "%.3f", extractMs)
                    : oneDecimal((float) extractMs);
            StringBuilder lights = new StringBuilder("§6[MetalMod]§r lights §b")
                    .append(net.metalmod.lighting.LightCollector.current().lights().size())
                    .append("/").append(net.metalmod.lighting.LightSnapshot.CAPACITY)
                    .append("§r drop §b").append(net.metalmod.lighting.LightCollector.dropped())
                    .append("§r | ").append(cost.clustered() ? "clustered" : "flat")
                    .append(" §b").append(extract).append(" ms")
                    .append(extractBreakdown(cost.extractNanos(), cost.entityQueryNanos(),
                            cost.blockIndexNanos()))
                    .append("§r | §b")
                    .append(cost.uploadBytes() / 1024).append(" KiB");
            // Buried sources are an anomaly, so they are named only when there are any: a permanent
            // "buried 0" is width spent on the normal case.
            int buried = net.metalmod.lighting.LightCollector.occluded();
            if (buried > 0) {
                lights.append("§r | buried §b").append(buried);
            }
            // Cluster occupancy is how the scaling gate is read off, and it only exists when the
            // clustered variant published. overflow and evict are the two that mean a cell was full.
            if (cost.clustered()) {
                var cluster = net.metalmod.backend.MetalDevice.lastClusterStats();
                lights.append("§r | cells §b").append(cluster.cellsTouched())
                        .append("§r occ §b").append(cluster.occupancyMax())
                        .append("/").append(cluster.occupancyMeanTimes100() / 100.0)
                        .append("§r full §b").append(cluster.cellsOverflowing())
                        .append("§r evict §b").append(cluster.evicted());
            }
            displayer.addLine(lights.toString());
            // The static index says whether "no per-frame world scan" is holding. Pending is the only
            // number that changes while standing still, so scans and evictions ride along with it.
            var blocks = net.metalmod.lighting.BlockLightIndex.stats();
            displayer.addLine("§6[MetalMod]§r blocks §b" + blocks.emitters() + "/"
                    + blocks.sections() + "§r | pend §b" + blocks.pendingSections()
                    + "§r | scan §b" + blocks.scans() + "§r evict §b" + blocks.evictions());
            var environment = net.metalmod.lighting.LightCollector.environment();
            if (environment.known()) {
                displayer.addLine("§6[MetalMod]§r env " + environment.summary());
            }
        }
        if (net.metalmod.debug.CaptureRouteRecorder.isActive()) {
            displayer.addLine("§6[MetalMod]§r §cREC§r route: "
                    + net.metalmod.debug.CaptureRouteRecorder.sampleCount()
                    + " samples §7(F7 stops; F8 replays a recorded route)§r");
        }

        // The backend's health counters. These are the numbers that explain a black or missing
        // object: a shader sampling something nothing bound, an attribute dropped from the vertex
        // descriptor, or a pipeline that failed to build and is therefore skipping its draws.
        int unbound = net.metalmod.backend.MetalDevice.unboundBindingCount();
        int unmapped = net.metalmod.backend.MetalDevice.missingVertexAttributeCount();
        int failed = net.metalmod.backend.MetalDevice.pipelineFailureCount();
        // The label that explains the three numbers is only worth its width when one of them is not
        // zero: in the normal case the caller knows what the line is, and in the bad case they need
        // telling.
        boolean healthy = unbound == 0 && unmapped == 0 && failed == 0;
        displayer.addLine("§6[MetalMod]§r health §b" + unbound + "/" + unmapped + "/" + failed
                + (healthy ? "§a ok" : "§r §7(unbound, missing attributes, pipeline builds)§r"));

        // Private storage is opted into by policy, not by proof, so the refusal counter is what says
        // whether the policy was right: it must stay at zero. The first number is how many textures
        // the rule actually claimed, which is zero only when the feature is switched off.
        int privateCpu = net.metalmod.backend.MetalDevice.privateCpuAccessCount();
        if (privateCpu != 0) {
            displayer.addLine("§6[MetalMod]§r §cprivate CPU access " + privateCpu
                    + "§r §7(a texture needs COPY_SRC/COPY_DST - see the log)§r");
        } else if (net.metalmod.backend.MetalDevice.privateTextureCount() > 0) {
            displayer.addLine("§6[MetalMod]§r private textures §b"
                    + net.metalmod.backend.MetalDevice.privateTextureCount() + "§r");
        }

        if (MetalConfig.INSTANCE.enableUnifiedMemoryPool && MetalBridge.isAvailable()) {
            UnifiedMemoryManager mem = UnifiedMemoryManager.getInstance();
            displayer.addLine("§6[MetalMod]§r UMA §b"
                    + UnifiedMemoryManager.formatBytes(mem.getProcessResident())
                    + "§r " + mem.getPressureString());
        }

        // Surfaces problems without needing the game log: the F3 screen is the one place a user
        // reliably looks when something is wrong. Only the hooks that did *not* apply are named -
        // listing all seven every frame was the longest line on the page, spent on the good case.
        // The two screen hooks are excluded inside Diagnostics rather than here, because "not yet
        // opened the settings" is not something this line can distinguish from "the injection failed".
        String missing = net.metalmod.Diagnostics.missing();
        if (!missing.isEmpty()) {
            displayer.addLine("§6[MetalMod]§r §chooks missing§r " + missing);
        }
    }

    /**
     * Show in both normal and reduced debug-info modes. This is diagnostic output, and hiding it
     * behind "reduced debug info" would make failures invisible exactly when they matter.
     */
    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    private static String oneDecimal(float value) {
        // LOCALE.ROOT: under a German locale the default format prints "17,5", and the capture
        // summary is pinned the same way.
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * Name the largest part of the extraction time, or nothing when extraction is too fast to split.
     *
     * <p>"flat 7.4 ms" says the feature is costing a fifth of the frame but not where, and the two
     * halves have opposite fixes: the entity query is loaded-world work that a section-relative query
     * or a cheaper predicate would cut, while index time is the static scan budget. The remainder is
     * named too, so the three cases - entities, index, other - are distinguishable rather than the
     * breakdown quietly attributing other people's milliseconds to whichever half is larger.
     */
    static String extractBreakdown(long extractNanos, long entityNanos, long blockNanos) {
        // Below a tenth of a millisecond the split is measurement noise and the width is better spent.
        if (extractNanos < 100_000L) return "";
        long otherNanos = Math.max(0L, extractNanos - entityNanos - blockNanos);
        long largest = Math.max(entityNanos, Math.max(blockNanos, otherNanos));
        String label = largest == entityNanos ? "ent" : largest == blockNanos ? "idx" : "oth";
        return " (" + label + " " + oneDecimal(largest / 1_000_000f) + ")";
    }
}
