package net.metalmod.upscaling;

import java.util.Arrays;

/** Measure real-only cadence before opting into interpolation; periodically recheck headroom. */
public final class FrameGenerationCadence {
    private final double[] samples=new double[24];
    private int count,generated;
    private boolean active;
    public void reset(){count=generated=0;active=false;}
    public void sample(double seconds){
        if(!active&&Double.isFinite(seconds)&&seconds>0&&seconds<0.25)
            samples[count++%samples.length]=seconds;
    }
    public boolean generate(double displayPeriod){return generate(displayPeriod,true);}
    /** A deliberate slow frame cap cannot reveal higher native headroom; retain active generation. */
    public boolean generate(double displayPeriod,boolean probeHeadroom){
        if(active){
            if(!probeHeadroom)return true;
            if(++generated<120)return true;
            active=false;count=0;generated=0;return false;
        }
        if(count<samples.length)return false;
        double[] ordered=samples.clone();Arrays.sort(ordered);
        // Slow frames must be sustained, not a loading/compilation spike.
        active=ordered[ordered.length/2]>displayPeriod*1.5;
        if(!active)count=0;
        return active;
    }
}
