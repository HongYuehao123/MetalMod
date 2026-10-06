package net.metalmod.benchmark;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
/** Optional test library observes real drawable timestamps without changing presentation. */
public final class DisplayProbe {
    private static MethodHandle observe,reset,snapshot,activate;
    static {
        String path=System.getProperty("metalmod.displayProbe");
        if(path!=null) {
            var lookup=SymbolLookup.libraryLookup(Path.of(path),Arena.global());var linker=Linker.nativeLinker();
            activate=linker.downcallHandle(lookup.find("benchmark_display_activate").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT));
            observe=linker.downcallHandle(lookup.find("benchmark_display_observe").orElseThrow(),FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            reset=linker.downcallHandle(lookup.find("benchmark_display_reset").orElseThrow(),FunctionDescriptor.ofVoid());
            snapshot=linker.downcallHandle(lookup.find("benchmark_display_snapshot").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT));
        }
    }
    public static void observe(MemorySegment drawable) {if(observe==null)return;try{observe.invokeExact(drawable);}catch(Throwable e){throw new RuntimeException(e);}}
    public static void activate() {if(activate==null)return;try{int status=(int)activate.invokeExact();if(status!=0)throw new IllegalStateException("benchmark hosting screen unavailable");}catch(Throwable e){throw new RuntimeException(e);}}
    public static void reset() {if(reset==null)return;try{reset.invokeExact();}catch(Throwable e){throw new RuntimeException(e);}}
    public static void save(Path folder) throws Exception {
        if(snapshot==null)return;
        try(var arena=Arena.ofConfined()) {
            var data=arena.allocate(ValueLayout.JAVA_LONG,12002);int n;
            try{n=(int)snapshot.invokeExact(data,12002);}catch(Throwable e){throw new RuntimeException(e);}
            StringBuilder csv=new StringBuilder("presented_ns\n");
            for(int i=2;i<n;i++)csv.append(data.getAtIndex(ValueLayout.JAVA_LONG,i)).append('\n');
            Files.writeString(folder.resolve("display.csv"),csv);
            Files.writeString(folder.resolve("display-counts.txt"),"Observed submissions: "+data.getAtIndex(ValueLayout.JAVA_LONG,0)+"\nCallbacks: "+data.getAtIndex(ValueLayout.JAVA_LONG,1)+"\nIncludes five-second countdown and export tail; constant present mode.\n");
        }
    }
}
