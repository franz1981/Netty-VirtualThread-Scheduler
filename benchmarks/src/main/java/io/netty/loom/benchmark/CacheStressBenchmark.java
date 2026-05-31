/*
 * Copyright 2026 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package io.netty.loom.benchmark;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import io.netty.loom.scheduler.EventLoopScheduler;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;
import io.netty.loom.scheduler.NettyScheduler;

/**
 * External submission benchmark: a single JMH thread creates N virtual threads
 * that each hash the same array, then awaits completion.
 *
 * <p>
 * Three workload configurations stress different aspects of the scheduler:
 *
 * <ul>
 * <li><b>128 × 4KB (tiny tasks)</b> — each task runs ~36ns. Tests the
 * scheduler's ability to concentrate burst submissions on a single carrier
 * without park/unpark storms. FJP achieves this via per-thread sticky probe +
 * conditional signalWork. The dominant cost is VT creation + park/unpark
 * overhead, not the task itself.
 *
 * <li><b>32 × 32MB (large tasks, 2:1 ratio)</b> — each task runs ~7ms across 16
 * cores. Tests work redistribution: all tasks initially land on 1-2 carriers
 * (sticky idle bitmap), requiring work-stealing to parallelize. Exercises the
 * drain-loop signal, steal propagation chain, and the balance between
 * concentration (initial placement) and distribution (WS).
 *
 * <li><b>16 × 32MB (1:1 ratio)</b> — each task runs ~7ms, one per carrier.
 * Tests the carrier model's strength: pinned carriers with warm cache, no
 * redistribution needed. This is the case where we beat FJP (+18-21%) because
 * pinned carriers avoid cpu-migrations. No work-stealing involved.
 * </ul>
 *
 * <h3>How to run</h3>
 *
 * Build:
 * 
 * <pre>{@code
 * export JAVA_HOME=/home/forked_franz/CLionProjects/loom/build/linux-x86_64-server-release/jdk
 * mvn install -DskipTests && mvn -pl benchmarks package -q -DskipTests
 * }</pre>
 *
 * Run all configs for a given workload (sequential, JMH does not support
 * parallel forks):
 * 
 * <pre>{@code
 * # 16 physical cores (taskset 0-15):
 * taskset -c 0-15 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWs$)" \
 *   -p numTasks=128 -p arraySizeKB=4 -wi 5 -i 5 -f 2 -t 1
 * taskset -c 0-15 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWs$)" \
 *   -p numTasks=32 -p arraySizeKB=32768 -wi 5 -i 5 -f 2 -t 1
 *
 * # 32 logical cores including SMT (taskset 0-31):
 * taskset -c 0-31 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWs$)" \
 *   -p numTasks=128 -p arraySizeKB=4 -wi 5 -i 5 -f 2 -t 1
 * taskset -c 0-31 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWs$)" \
 *   -p numTasks=64 -p arraySizeKB=32768 -wi 5 -i 5 -f 2 -t 1
 * }</pre>
 *
 * <h3>Reference results (2026-05-30, Ryzen 9 7950X)</h3>
 *
 * <p>
 * nSearching chain + bitmap scan + state-encoded steal target:
 *
 * <pre>
 * 16 cores (taskset 0-15):
 *                              128×4KB (ops/s)    32×32MB (ops/s)
 * custom (no WS)                 44,991               5
 * customPinWs (global WS)        42,192             474
 * customPinWsCluster (cluster)   38,391             472
 * FJP                            28,314             440
 *
 * 32 cores including SMT (taskset 0-31):
 *                              128×4KB (ops/s)    64×32MB (ops/s)
 * custom (no WS)                 42,279               3
 * customPinWs (global WS)        42,214             303
 * customPinWsCluster (cluster)   43,037             304
 * FJP                            32,307             347
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Benchmark)
public class CacheStressBenchmark {

	private static final String[] COMMON = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch"};
	private static final String IMPL = "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler";
	private static final String TOPO = "-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology";
	private static final String WS = "-Dio.netty.loom.workstealing.enabled=true";
	private static final String WS0 = "-Dio.netty.loom.workstealing.unresponsive.us=50";
	private static final String CL = "-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL";

	@Param({"128"})
	int numTasks;

	@Param({"4"})
	int arraySizeKB;

	private static final MethodHandle CURRENT_CARRIER;
	static {
		try {
			CURRENT_CARRIER = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
					.findStatic(Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static Thread currentCarrier() {
		try {
			return (Thread) CURRENT_CARRIER.invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	final ConcurrentHashMap<String, LongAdder> carrierCounts = new ConcurrentHashMap<>();

	int[] data;
	ThreadFactory vtFactory;
	int[] perInvocationCarriers;
	int[] taskCounter;
	long totalDistinct;
	long totalInvocations;

	@Setup(Level.Trial)
	public void setup() {
		int elements = (int) (arraySizeKB * 1024L / Integer.BYTES);
		data = ThreadLocalRandom.current().ints(elements).toArray();

		if (NettyScheduler.isAvailable()) {
			vtFactory = EventLoopSchedulerGroup.instance().virtualThreadFactory();
		} else {
			vtFactory = Thread.ofVirtual().factory();
		}

		System.out.println("[setup] scheduler=" + (NettyScheduler.isAvailable()
				? "custom(" + EventLoopSchedulerGroup.instance().size() + " carriers, "
						+ EventLoopSchedulerGroup.instance().clusterCount() + " clusters)"
				: "FJP") + " array=" + arraySizeKB + "KB tasks=" + numTasks);
	}

	private int cacheStress() throws InterruptedException {
		if (perInvocationCarriers != null) {
			Arrays.fill(perInvocationCarriers, -1);
			taskCounter[0] = 0;
		}
		var latch = new CountDownLatch(numTasks);
		var results = new int[numTasks];
		for (int i = 0; i < numTasks; i++) {
			final int idx = i;
			final int[] d = data;
			vtFactory.newThread(() -> {
				carrierCounts.computeIfAbsent(currentCarrier().getName(), k -> new LongAdder()).increment();
				results[idx] = Arrays.hashCode(d);
				latch.countDown();
			}).start();
		}
		latch.await();
		if (perInvocationCarriers != null) {
			long distinct = Arrays.stream(perInvocationCarriers).distinct().filter(x -> x >= 0).count();
			totalDistinct += distinct;
			totalInvocations++;
		}
		int result = 0;
		for (int r : results) {
			result ^= r;
		}
		return result;
	}

	@TearDown(Level.Trial)
	public void printStats() {
		if (totalInvocations > 0) {
			System.out.println("[idle-distribution] avg distinct carriers per invocation: "
					+ String.format("%.1f", (double) totalDistinct / totalInvocations) + " / " + numTasks + " tasks ("
					+ totalInvocations + " invocations)");
		}
		if (!carrierCounts.isEmpty()) {
			System.out.println("[carriers] " + carrierCounts);
		}
	}

	private static ThreadFactory roundRobinFactory(EventLoopSchedulerGroup group) {
		var counter = new AtomicInteger();
		return runnable -> {
			int idx = counter.getAndIncrement() % group.size();
			return group.scheduler(idx).virtualThreadFactory().newThread(runnable);
		};
	}

	private static ThreadFactory batchFactory(EventLoopSchedulerGroup group, int numTasks) {
		int batchSize = Math.max(1, numTasks / group.size());
		var counter = new int[]{0};
		return runnable -> {
			int idx = (counter[0]++ / batchSize) % group.size();
			return group.scheduler(idx).virtualThreadFactory().newThread(runnable);
		};
	}

	private static ThreadFactory clusterFactory(EventLoopSchedulerGroup group, int clusterIndex) {
		var cluster = group.cluster(clusterIndex);
		var counter = new AtomicInteger();
		return runnable -> {
			int idx = counter.getAndIncrement() % cluster.length;
			return cluster[idx].virtualThreadFactory().newThread(runnable);
		};
	}

	/** Baseline — raw hashCode, no VT, no scheduler. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch"})
	public int baseline() {
		int result = 0;
		for (int i = 0; i < numTasks; i++) {
			result ^= Arrays.hashCode(data);
		}
		return result;
	}

	/** Custom scheduler, pinned, WS, tasks confined to one LLC cluster. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.unresponsive.us=50",
			"-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL"})
	public int customClusterAffine() throws InterruptedException {
		var group = EventLoopSchedulerGroup.instance();
		var factory = clusterFactory(group, 0);
		var latch = new CountDownLatch(numTasks);
		var results = new int[numTasks];
		for (int i = 0; i < numTasks; i++) {
			final int idx = i;
			final int[] d = data;
			factory.newThread(() -> {
				carrierCounts.computeIfAbsent(currentCarrier().getName(), k -> new LongAdder()).increment();
				results[idx] = Arrays.hashCode(d);
				latch.countDown();
			}).start();
		}
		latch.await();
		int result = 0;
		for (int r : results) {
			result ^= r;
		}
		return result;
	}

	/** Vanilla ForkJoinPool — baseline. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch"})
	public int fjp() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, no pinning, no work-stealing. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler"})
	public int custom() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, no pinning, aggressive work-stealing (global). */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.unresponsive.us=50"})
	public int customWs() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned to cores, no work-stealing. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology"})
	public int customPin() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned, aggressive work-stealing (global scope). */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.unresponsive.us=50"})
	public int customPinWs() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned, aggressive work-stealing (cluster-local scope). */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.unresponsive.us=50",
			"-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL"})
	public int customPinWsCluster() throws InterruptedException {
		return cacheStress();
	}

	static long readL3CacheSize() {
		for (int i = 0; i < 10; i++) {
			try {
				String dir = "/sys/devices/system/cpu/cpu0/cache/index" + i;
				if (!Files.exists(Path.of(dir))) {
					break;
				}
				String level = Files.readString(Path.of(dir + "/level")).trim();
				if ("3".equals(level)) {
					return parseCacheSize(Files.readString(Path.of(dir + "/size")).trim());
				}
			} catch (Exception e) {
				break;
			}
		}
		return 32L * 1024 * 1024;
	}

	private static long parseCacheSize(String size) {
		size = size.trim().toUpperCase();
		if (size.endsWith("K")) {
			return Long.parseLong(size.substring(0, size.length() - 1)) * 1024;
		}
		if (size.endsWith("M")) {
			return Long.parseLong(size.substring(0, size.length() - 1)) * 1024 * 1024;
		}
		return Long.parseLong(size);
	}
}
