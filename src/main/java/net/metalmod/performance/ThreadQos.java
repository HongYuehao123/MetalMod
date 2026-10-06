package net.metalmod.performance;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Opt-in scheduling experiment. Each platform thread sets its own QoS once; workers keep theirs. */
public final class ThreadQos {
    private static final boolean ENABLED = Boolean.getBoolean("metalmod.threadQos");
    private static final ThreadLocal<Boolean> ATTEMPTED = ThreadLocal.withInitial(() -> false);

    private ThreadQos() {}

    public static void renderThread() { apply("render", 0x21); }
    public static void serverThread() { apply("integrated server", 0x19); }

    private static void apply(String role, int qos) {
        if (!ENABLED || Thread.currentThread().isVirtual() || ATTEMPTED.get()) return;
        ATTEMPTED.set(true);
        try {
            Linker linker = Linker.nativeLinker();
            MethodHandle set = linker.downcallHandle(
                    linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int result = (int) set.invokeExact(qos, 0);
            System.out.println("[MetalMod] QoS experiment " + role + " rc=" + result);
        } catch (Throwable error) {
            System.err.println("[MetalMod] QoS experiment unavailable for " + role + ": " + error);
        }
    }
}
