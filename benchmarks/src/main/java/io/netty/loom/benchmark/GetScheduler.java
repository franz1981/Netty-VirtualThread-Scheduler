package io.netty.loom.benchmark;

import io.netty.loom.LoomSupport;
import io.netty.loom.VirtualThreadNettyScheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Measurement(iterations = 10, time = 1)
@Warmup(iterations = 10, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions"})
public class GetScheduler {

    private static final Executor vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Thread.VirtualThreadScheduler fromJDKPublicMethod() {
       return CompletableFuture.supplyAsync(Thread.VirtualThreadScheduler::current, vtExecutor).join();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Thread.VirtualThreadScheduler fromJDKInternalField() {
        return CompletableFuture.supplyAsync(() -> LoomSupport.getScheduler(Thread.currentThread()), vtExecutor).join();
    }
}
