package io.netty.loom.benchmark;

import io.netty.loom.LoomSupport;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Measurement(iterations = 10, time = 1)
@Warmup(iterations = 10, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions"})
public class GetCarrierThread {

    private static final MethodHandle CARRIER_THREAD_FIELD;
    static {
        try {
            var field = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("carrierThread");
            field.setAccessible(true);
            MethodHandle carrierThreadMh = MethodHandles.lookup().unreflectGetter(field);
            // adapt using VirtualThread into Thread
            CARRIER_THREAD_FIELD = carrierThreadMh.asType(MethodType.methodType(Thread.class, Thread.class));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static Thread getCarrierThread(Thread t) {
        if (!t.isVirtual()) {
            return t;
        }
        try {
            return (Thread) CARRIER_THREAD_FIELD.invokeExact(t);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private Thread vThread;

    @Setup
    public void init() {
        vThread = Thread.ofVirtual().unstarted(() -> {
        });
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Thread carrierThreadMh() {
        return getCarrierThread(vThread);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Thread volatileCarrierThreadVh() {
        return LoomSupport.getCarrierThread(vThread);
    }
}
