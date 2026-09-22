package net.metalmod.backend;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import java.util.OptionalDouble;

/**
 * Phase 1 sampler: records the configuration the engine expects but creates no MTLSamplerState,
 * because no pass binds it. Phase 2 materialises it.
 */
public final class MetalSampler extends GpuSampler {

    private final AddressMode addressU;
    private final AddressMode addressV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;

    public MetalSampler(AddressMode addressU, AddressMode addressV,
                        FilterMode minFilter, FilterMode magFilter,
                        int maxAnisotropy, OptionalDouble maxLod) {
        this.addressU = addressU;
        this.addressV = addressV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        // no native object yet
    }
}
