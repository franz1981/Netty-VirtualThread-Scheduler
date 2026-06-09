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
 * External submission benchmark: JMH thread(s) create N virtual threads that
 * each hash the same array, then await completion.
 *
 * <p>
 * The custom scheduler uses a probe factory: each submitter thread's ID is
 * xorshift-hashed to a carrier, concentrating each thread's bursts on a
 * dedicated MPSC queue. Different submitter threads scatter across carriers.
 *
 * <h3>Workload configurations</h3>
 *
 * <ul>
 * <li><b>128 × 4KB (tiny tasks, -t 1..3)</b> — each task runs ~36ns. Tests
 * burst submission throughput. With -t 1, one carrier drains the burst; FJP
 * distributes across all workers. With -t 2+, each submitter targets a
 * different carrier via the probe factory, scaling linearly.
 *
 * <p>
 * <b>Caveat:</b> with -t 1 and {@code taskset -c 0-15} on a 2-NUMA-node machine
 * (e.g. Ryzen 7950X), the probe may hash the submitter to a carrier pinned on a
 * remote NUMA node, causing cross-NUMA MPSC contention. Use
 * {@code taskset -c 0-7} (single NUMA node) for fair single-thread comparison.
 * FJP is not affected because its shared external queues are drained by the
 * nearest worker.
 *
 * <li><b>32 × 32MB (large tasks, 2:1 ratio)</b> — each task runs ~7ms across 16
 * cores. Tests work redistribution: all tasks initially land on one carrier,
 * requiring work-stealing to parallelize.
 *
 * <li><b>16 × 32MB (1:1 ratio)</b> — each task runs ~7ms, one per carrier.
 * Tests the carrier model's strength: pinned carriers with warm cache.
 * </ul>
 *
 * <h3>How to run</h3>
 *
 * Build:
 *
 * <pre>{@code
 * export JAVA_HOME=/path/to/loom/jdk
 * mvn -B clean package -DskipTests -P '!dev'
 * }</pre>
 *
 * Run (single NUMA node for short tasks, full machine for large tasks):
 *
 * <pre>{@code
 * # 8 cores (single NUMA node):
 * taskset -c 0-7 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWs$|customPinWsPoller$)" \
 *   -p numTasks=128 -p arraySizeKB=4 -wi 5 -i 5 -f 2 -t 1
 * # Same with multiple submitters:
 * taskset -c 0-7 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWsPoller$)" \
 *   -p numTasks=128 -p arraySizeKB=4 -wi 5 -i 5 -f 2 -t 3
 * # 16 cores (large tasks):
 * taskset -c 0-15 $JAVA_HOME/bin/java -jar benchmarks/target/benchmarks.jar \
 *   "CacheStressBenchmark.(fjp|customPinWsPoller$)" \
 *   -p numTasks=32 -p arraySizeKB=32768 -wi 5 -i 5 -f 2 -t 1
 * }</pre>
 *
 * <h3>Reference results (2026-06-09, Ryzen 9 7950X)</h3>
 *
 * <pre>
 * 8 cores (taskset 0-7, single NUMA node), 2 forks, 5wu, 5i:
 *
 *                          128×4KB -t 1   128×4KB -t 2   128×4KB -t 3
 * customWs (no pin)       40,704          68,746          68,571
 * customPinWs             40,478          70,277          78,535
 * customPinWsPoller       42,257          68,890         105,342
 * FJP                     43,108          65,393          75,320
 *
 * 16 cores (taskset 0-15), 2 forks, 5wu, 5i:
 *                          32×32MB -t 1
 * customPinWsPoller         487 ± 32
 * FJP                       434 ± 16
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Benchmark)
public class CacheStressBenchmark {

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

	static final class SingleThreadedAdder {
		long value;
		void increment() {
			value++;
		}
		long get() {
			return value;
		}
		void reset() {
			value = 0;
		}
		@Override
		public String toString() {
			return Long.toString(value);
		}
	}

	final ConcurrentHashMap<Thread, SingleThreadedAdder> carrierCounts = new ConcurrentHashMap<>();

	private static final boolean TELEMETRY = Boolean.getBoolean("io.netty.loom.benchmark.telemetry");

	int[] data;
	ThreadFactory vtFactory;
	long totalDistinct;
	long totalInvocations;
	long[] rankTotals;
	AutoCloseable pollerGroup;

	@Setup(Level.Trial)
	public void setup() {
		int elements = (int) (arraySizeKB * 1024L / Integer.BYTES);
		data = ThreadLocalRandom.current().ints(elements).toArray();

		if (NettyScheduler.isAvailable()) {
			vtFactory = EventLoopSchedulerGroup.instance().virtualThreadFactory();
			if (Boolean.getBoolean("io.netty.loom.benchmark.epollPoller")) {
				try {
					pollerGroup = createEpollPollerGroup();
					System.out.println("[setup] epoll pinned poller group created");
				} catch (Exception e) {
					throw new RuntimeException("Failed to create epoll poller group", e);
				}
			} else if (Boolean.getBoolean("io.netty.loom.benchmark.nioPoller")) {
				try {
					pollerGroup = createNioPollerGroup();
					System.out.println("[setup] NIO poller group created");
				} catch (Exception e) {
					throw new RuntimeException("Failed to create NIO poller group", e);
				}
			}
		} else {
			vtFactory = Thread.ofVirtual().factory();
		}

		if (TELEMETRY) {
			rankTotals = new long[numTasks];
		}
		System.out.println("[setup] scheduler="
				+ (NettyScheduler.isAvailable()
						? "custom(" + EventLoopSchedulerGroup.instance().size() + " carriers, "
								+ EventLoopSchedulerGroup.instance().clusterCount() + " clusters)"
						: "FJP")
				+ " array=" + arraySizeKB + "KB tasks=" + numTasks + " poller="
				+ (pollerGroup != null ? "epoll" : "none"));
	}

	private static AutoCloseable createEpollPollerGroup() {
		var epollFactory = io.netty.channel.epoll.EpollIoHandler.newFactory();
		return new io.netty.loom.VirtualIoNativePollerEventLoopGroup(epollFactory);
	}

	private static AutoCloseable createNioPollerGroup() {
		var nioFactory = io.netty.channel.nio.NioIoHandler.newFactory();
		return new io.netty.loom.VirtualIoNioPollerEventLoopGroup(nioFactory);
	}

	private int cacheStress() throws InterruptedException {
		return cacheStressWithFactory(vtFactory);
	}

	private int cacheStressWithFactory(ThreadFactory factory) throws InterruptedException {
		if (TELEMETRY) {
			carrierCounts.values().forEach(SingleThreadedAdder::reset);
		}
		var latch = new CountDownLatch(numTasks);
		var results = new int[numTasks];
		for (int i = 0; i < numTasks; i++) {
			final int idx = i;
			final int[] d = data;
			factory.newThread(() -> {
				if (TELEMETRY) {
					var carrier = currentCarrier();
					var c = carrierCounts.get(carrier);
					if (c == null)
						c = carrierCounts.computeIfAbsent(carrier, k -> new SingleThreadedAdder());
					c.increment();
				}
				results[idx] = Arrays.hashCode(d);
				latch.countDown();
			}).start();
		}
		latch.await();
		if (TELEMETRY) {
			var counts = carrierCounts.values().stream().mapToLong(SingleThreadedAdder::get).filter(v -> v > 0).sorted()
					.toArray();
			totalDistinct += counts.length;
			totalInvocations++;
			for (int r = 0; r < counts.length; r++) {
				rankTotals[r] += counts[counts.length - 1 - r];
			}
		}
		int result = 0;
		for (int r : results) {
			result ^= r;
		}
		return result;
	}

	@TearDown(Level.Trial)
	public void printStats() {
		if (TELEMETRY && totalInvocations > 0) {
			var sb = new StringBuilder();
			sb.append("[distribution] avg carriers/invocation: ")
					.append(String.format("%.1f", (double) totalDistinct / totalInvocations)).append(" (")
					.append(totalInvocations).append(" invocations, ").append(numTasks).append(" tasks each)\n");
			sb.append("[exec-avg]   ");
			for (int r = 0; r < rankTotals.length && rankTotals[r] > 0; r++) {
				double avg = (double) rankTotals[r] / totalInvocations;
				sb.append(String.format("#%d=%.1f(%.0f%%) ", r + 1, avg, avg * 100.0 / numTasks));
			}
			System.out.println(sb);
		}
		if (pollerGroup != null) {
			try {
				pollerGroup.close();
				System.out.println("[teardown] poller group closed");
			} catch (Exception e) {
				System.err.println("[teardown] poller group close failed: " + e);
			}
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
			int idx = counter.getAndIncrement() % cluster.size();
			return cluster.get(idx).virtualThreadFactory().newThread(runnable);
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
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL"})
	public int customClusterAffine() throws InterruptedException {
		var group = EventLoopSchedulerGroup.instance();
		var factory = clusterFactory(group, 0);
		var latch = new CountDownLatch(numTasks);
		var results = new int[numTasks];
		for (int i = 0; i < numTasks; i++) {
			final int idx = i;
			final int[] d = data;
			factory.newThread(() -> {
				if (TELEMETRY) {
					var carrier = currentCarrier();
					var c = carrierCounts.get(carrier);
					if (c == null)
						c = carrierCounts.computeIfAbsent(carrier, k -> new SingleThreadedAdder());
					c.increment();
				}
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
			"-Dio.netty.loom.workstealing.enabled=true"})
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
			"-Dio.netty.loom.workstealing.enabled=true"})
	public int customPinWs() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned, aggressive work-stealing (cluster-local scope). */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL"})
	public int customPinWsCluster() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned, NO WS, epoll pinned poller on all carriers. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.benchmark.epollPoller=true"})
	public int customPinPoller() throws InterruptedException {
		return cacheStress();
	}

	/**
	 * Custom scheduler, pinned, WS (global), epoll pinned poller on all carriers.
	 */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.benchmark.epollPoller=true"})
	public int customPinWsPoller() throws InterruptedException {
		return cacheStress();
	}

	/** Custom scheduler, pinned, WS (global), NIO poller on all carriers. */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.benchmark.nioPoller=true"})
	public int customPinWsNioPoller() throws InterruptedException {
		return cacheStress();
	}

	/**
	 * Custom scheduler, pinned, WS (cluster-local), epoll pinned poller on all
	 * carriers.
	 */
	@Benchmark
	@Fork(value = 2, jvmArgs = {"--enable-preview", "--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--enable-native-access=ALL-UNNAMED", "-Djdk.trackAllThreads=false", "-XX:+UseNUMA", "-Xms4g", "-Xmx4g",
			"-XX:+AlwaysPreTouch", "-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler",
			"-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology",
			"-Dio.netty.loom.workstealing.enabled=true", "-Dio.netty.loom.workstealing.scope=CLUSTER_LOCAL",
			"-Dio.netty.loom.benchmark.epollPoller=true"})
	public int customPinWsClusterPoller() throws InterruptedException {
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
