package io.netty.loom.benchmark;

import io.netty.channel.nio.NioIoHandler;
import io.netty.loom.LoomSupport;
import io.netty.loom.MultithreadVirtualEventExecutorGroup;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
        "-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler"})
@State(Scope.Thread)
public class NettySchedulerBenchmark {

    @Param({"1000", "100000"})
    private int tasks;

    private MultithreadVirtualEventExecutorGroup executorGroup;

    private ThreadFactory vtFactory;

    @Setup
    public void setup() throws ExecutionException, InterruptedException {
        executorGroup = new MultithreadVirtualEventExecutorGroup(1, NioIoHandler.newFactory());
        vtFactory = executorGroup.submit(executorGroup::vThreadFactory).get();
    }

    @Benchmark
    public void global() {
        CountDownLatch countDown = new CountDownLatch(tasks);
        vtFactory.newThread(
                () -> {
                    for (int i = 0; i < tasks; i++) {
                        Thread.startVirtualThread(countDown::countDown);
                    }
                }
        ).start();
        try {
            countDown.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void inheritFromParent() {
        CountDownLatch countDown = new CountDownLatch(tasks);
        vtFactory.newThread(
                () -> {
                    Thread.VirtualThreadScheduler parentScheduler = LoomSupport.getScheduler(Thread.currentThread());
                    // Simulate the behavior of the previous version
                    // get the scheduler from the parent thread before starting, instead of pre-initializing the `vtFactory`.
                    for (int i = 0; i < tasks; i++) {
                        Thread.ofVirtual()
                                .scheduler(parentScheduler)
                                .start(countDown::countDown);
                    }
                }
        ).start();
        try {
            countDown.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @TearDown
    public void tearDown() {
        executorGroup.shutdownGracefully();
    }
}
