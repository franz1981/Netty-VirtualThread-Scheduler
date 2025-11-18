package io.netty.loom.benchmark;

import io.netty.channel.nio.NioIoHandler;
import io.netty.loom.VirtualMultithreadIoEventLoopGroup;
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
import org.openjdk.jmh.infra.BenchmarkParams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class NettySchedulerBenchmark {

	@Param({"1000", "100000"})
	private int tasks;

	private VirtualMultithreadIoEventLoopGroup executorGroup;

	private ThreadFactory vtFactory;

	@Setup
	public void setup(BenchmarkParams params) throws ExecutionException, InterruptedException {
		if (params.getBenchmark().contains("Netty")) {
			executorGroup = new VirtualMultithreadIoEventLoopGroup(1, NioIoHandler.newFactory());
			vtFactory = executorGroup.submit(executorGroup::vThreadFactory).get();
		}
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false",
			"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler", "-Djdk.pollerMode=3"})
	public void scheduleToFjFromNetty() {
		CountDownLatch countDown = new CountDownLatch(tasks);
		vtFactory.newThread(() -> {
			for (int i = 0; i < tasks; i++) {
				Thread.startVirtualThread(countDown::countDown);
			}
		}).start();
		try {
			countDown.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false"})
	public void scheduleToFjFromFjWithBuiltInScheduler() {
		CountDownLatch countDown = new CountDownLatch(tasks);
		Thread.startVirtualThread(() -> {
			for (int i = 0; i < tasks; i++) {
				Thread.startVirtualThread(countDown::countDown);
			}
		}).start();
		try {
			countDown.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false",
			"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler", "-Djdk.pollerMode=3"})
	public void scheduleToFjFromFjWithCustomScheduler() {
		CountDownLatch countDown = new CountDownLatch(tasks);
		Thread.startVirtualThread(() -> {
			for (int i = 0; i < tasks; i++) {
				Thread.startVirtualThread(countDown::countDown);
			}
		}).start();
		try {
			countDown.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Benchmark
	@Fork(value = 2, jvmArgs = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UnlockExperimentalVMOptions",
			"-XX:-DoJVMTIVirtualThreadTransitions", "-Djdk.trackAllThreads=false",
			"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler", "-Djdk.pollerMode=3"})
	public void scheduleToNettyFromNetty() {
		CountDownLatch countDown = new CountDownLatch(tasks);
		vtFactory.newThread(() -> {
			for (int i = 0; i < tasks; i++) {
				vtFactory.newThread(countDown::countDown).start();
			}
		}).start();
		try {
			countDown.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@TearDown
	public void tearDown() {
		if (executorGroup != null) {
			executorGroup.shutdownGracefully();
		}
	}
}
