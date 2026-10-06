package net.metalmod.performance;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated platform threads ensure this verification never changes the test runner's QoS. */
public final class ThreadQosTest {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("metalmod.threadQos")) throw new IllegalStateException("Enable the QoS experiment for this test");
        verify("render", ThreadQos::renderThread, 0x21);
        verify("server", ThreadQos::serverThread, 0x19);
        System.out.println("THREAD QOS CHECK PASSED");
    }

    private static void verify(String role, Runnable apply, int expected) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                apply.run();
                var linker = Linker.nativeLinker();
                var query = linker.downcallHandle(linker.defaultLookup().find("qos_class_self").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                int actual = (int) query.invokeExact();
                if (actual != expected) throw new AssertionError(role + " QoS " + actual + " != " + expected);
                System.out.println("PASS " + role + " native QoS=" + actual);
            } catch (Throwable error) { failure.set(error); }
        }, "metalmod-qos-check-" + role);
        thread.start();
        thread.join(5000);
        if (thread.isAlive()) throw new AssertionError("QoS check timed out");
        if (failure.get() != null) throw new AssertionError(role, failure.get());
    }
}
