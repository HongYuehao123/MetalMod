package net.metalmod.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;

/** Preserve Sodium's section/face ordering and index-element units through direct Metal draws. */
public final class MetalDrawBatch extends MultiDrawBatch {
    private int[] counts, bases, first;

    public MetalDrawBatch(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("batch capacity");
        counts = new int[capacity]; bases = new int[capacity]; first = new int[capacity];
    }

    @Override public void put(int slot, int elementCount, int baseVertex, long elementOffset) {
        if (elementCount < 0 || elementOffset < 0) throw new IllegalArgumentException("draw range");
        counts[slot] = elementCount; bases[slot] = baseVertex; first[slot] = Math.toIntExact(elementOffset);
        updateMaxElementCount(elementCount);
    }

    @Override public void draw(DrawContext context) {
        if (!(context instanceof MetalDrawContext)) throw new IllegalArgumentException("Metal batch/context mismatch");
        var pass = context.getPass();
        for (int i=0; i<size; ++i) pass.drawIndexed(counts[i], 1, first[i], bases[i], 0);
    }

    @Override public void delete() { counts = bases = first = null; }
}
