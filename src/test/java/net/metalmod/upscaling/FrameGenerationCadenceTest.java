package net.metalmod.upscaling;

/** Regression for real-rate preservation, loading spikes, and recovery from a heavy scene. */
public final class FrameGenerationCadenceTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static int runTests(){
        var cadence=new FrameGenerationCadence();double period=1.0/60;
        for(int i=0;i<72;i++){
            cadence.sample(i==12?0.15:period);
            require(!cadence.generate(period),"60 real FPS and a loading spike must not be forced to 30");
        }
        for(int i=0;i<24;i++)cadence.sample(1.0/30);
        require(cadence.generate(period),"sustained 30 real FPS permits intermediate frames");
        boolean probe=false;
        for(int i=0;i<121;i++)if(!cadence.generate(period)){probe=true;break;}
        require(probe,"interpolation must periodically remeasure native cadence");
        for(int i=0;i<48;i++){
            cadence.sample(period);
            require(!cadence.generate(period),"returning to a light scene must recover 60 real FPS");
        }
        cadence.reset();
        for(int i=0;i<24;i++)cadence.sample(1.0/30);
        for(int i=0;i<360;i++)require(cadence.generate(period,false),"an explicit 30 cap must not periodically drop generated delivery");
        cadence.reset();cadence.sample(Double.NaN);
        require(!cadence.generate(period),"invalid timing cannot enable generation");
        System.out.println("PASS adaptive frame generation preserves native cadence and recovers headroom");return 0;
    }
}
