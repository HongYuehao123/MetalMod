package net.metalmod.backend;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

/** A real MTLSamplerState built from the engine's address/filter/anisotropy/LOD parameters. */
public final class MetalSampler extends GpuSampler {

    private final AddressMode addressU;
    private final AddressMode addressV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final MemorySegment handle;
    private boolean closed;

    public MetalSampler(MetalDevice device, AddressMode addressU, AddressMode addressV,
                        FilterMode minFilter, FilterMode magFilter,
                        int maxAnisotropy, OptionalDouble maxLod) {
        this.addressU = addressU;
        this.addressV = addressV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;

        MemorySegment created = MetalNative.samplerCreate(device.deviceHandle(),
                MetalFormat.mtlSamplerAddress(addressU),
                MetalFormat.mtlSamplerAddress(addressV),
                MetalFormat.mtlSamplerFilter(minFilter),
                MetalFormat.mtlSamplerFilter(magFilter),
                MetalFormat.mtlSamplerMipFilter(maxLod.isPresent()),
                maxAnisotropy,
                maxLod.isPresent(),
                maxLod.orElse(0.0));
        this.handle = created == null ? MemorySegment.NULL : created;
        if (this.handle.address() == 0) {
            MetalDevice.reportResourceFailure("sampler " + addressU + "/" + addressV
                    + " " + minFilter + "/" + magFilter);
        }
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public boolean isValid() {
        return this.handle.address() != 0;
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
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.handle.address() != 0) {
            MetalNative.samplerRelease(this.handle);
        }
    }
}
