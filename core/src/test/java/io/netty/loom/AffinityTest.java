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
package io.netty.loom;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AffinityTest {

	private static final MethodHandle CURRENT_CARRIER;

	static {
		try {
			CURRENT_CARRIER = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
					.findStatic(Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private static Thread currentCarrier() {
		try {
			return (Thread) CURRENT_CARRIER.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	enum ParkStrategy {
		YIELD, TIMED_PARK, MIXED
	}

	private static void park(ParkStrategy strategy, long iteration) {
		switch (strategy) {
			case YIELD -> Thread.yield();
			case TIMED_PARK -> LockSupport.parkNanos(1);
			case MIXED -> {
				if ((iteration & 1) == 0) {
					Thread.yield();
				} else {
					LockSupport.parkNanos(1);
				}
			}
		}
	}

	private record VThreadStats(IdentityHashMap<Thread, AtomicLong> carrierAttempts, AtomicLong totalSwitches,
			AtomicLong totalIterations) {
		VThreadStats() {
			this(new IdentityHashMap<>(), new AtomicLong(), new AtomicLong());
		}
	}

	static Stream<Arguments> parameters() {
		var strategies = ParkStrategy.values();
		var builder = Stream.<Arguments>builder();
		for (var strategy : strategies) {
			builder.add(Arguments.of(strategy, true));
			builder.add(Arguments.of(strategy, false));
		}
		return builder.build();
	}

	@ParameterizedTest(name = "{0} affinity={1}")
	@MethodSource("parameters")
	public void testAffinityConvergence(ParkStrategy strategy, boolean affinity) throws InterruptedException {
		int n = Runtime.getRuntime().availableProcessors();
		var builder = Thread.ofVirtual();
		if (affinity) {
			builder.roundRobinAffinity();
		}
		var factory = builder.factory();
		Map<Thread, VThreadStats> perVThread = new ConcurrentHashMap<>();
		var stop = new AtomicBoolean();
		List<Thread> threads = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			threads.add(factory.newThread(() -> {
				var stats = new VThreadStats();
				perVThread.put(Thread.currentThread(), stats);
				for (long iter = 0; !stop.get(); iter++) {
					Thread beforePark = currentCarrier();
					park(strategy, iter);
					long iterations = stats.totalIterations.get() + 1;
					stats.totalIterations.lazySet(iterations);
					Thread afterPark = currentCarrier();
					var counter = stats.carrierAttempts.computeIfAbsent(afterPark, t -> new AtomicLong());
					counter.lazySet(counter.get() + 1);
					if (afterPark != beforePark) {
						stats.totalSwitches.lazySet(stats.totalSwitches.get() + 1);
					}
				}
			}));
		}
		threads.forEach(Thread::start);
		Thread.sleep(10_000);
		stop.set(true);
		for (var thread : threads) {
			thread.join(5_000);
		}

		System.out.println("Strategy: " + strategy + ", affinity=" + affinity + " (" + n + " virtual threads, "
				+ perVThread.size() + " reported)");
		long globalSwitches = 0;
		long globalIterations = 0;
		for (var entry : perVThread.entrySet()) {
			var stats = entry.getValue();
			long iterations = stats.totalIterations.get();
			if (stats.carrierAttempts.isEmpty()) {
				System.out.printf("%s => never scheduled%n", entry.getKey().getName());
				continue;
			}
			var dominant = stats.carrierAttempts.entrySet().stream()
					.max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get()))).orElseThrow();
			long switches = stats.totalSwitches.get();
			globalSwitches += switches;
			globalIterations += iterations;
			System.out.printf(
					"%s => dominant: %s (%d/%d = %.4f%% affinity, %d carriers used, switches: %d/%d = %.6f)%n",
					entry.getKey().getName(), dominant.getKey().getName(), dominant.getValue().get(), iterations,
					iterations > 0 ? 100.0 * dominant.getValue().get() / iterations : 0.0, stats.carrierAttempts.size(),
					switches, iterations, iterations > 0 ? (double) switches / iterations : 0.0);
		}
		System.out.printf("%nGlobal switches: %d / %d = %.6f%n%n", globalSwitches, globalIterations,
				globalIterations > 0 ? (double) globalSwitches / globalIterations : 0.0);
		System.out.flush();
	}

	/**
	 * Tests that short-lived virtual threads spawned by long-running VTs converge
	 * to the same carrier as their parent. N long-running VTs continuously
	 * park/yield; on each iteration they spawn a short-lived child VT that records
	 * its own carrier. We then check whether the child converges to a dominant
	 * carrier and whether that carrier matches the parent's dominant one.
	 */
	@ParameterizedTest(name = "{0} affinity={1}")
	@MethodSource("parameters")
	public void testChildAffinityConvergence(ParkStrategy strategy, boolean affinity) throws InterruptedException {
		int n = Runtime.getRuntime().availableProcessors();
		var parentBuilder = Thread.ofVirtual();
		if (affinity) {
			parentBuilder.roundRobinAffinity();
		}
		var parentFactory = parentBuilder.factory();
		// children inherit affinity from the parent VT
		var childFactory = affinity ? Thread.ofVirtual().inheritAffinity().factory() : Thread.ofVirtual().factory();

		record ParentChildStats(IdentityHashMap<Thread, AtomicLong> parentCarrierCounts,
				IdentityHashMap<Thread, AtomicLong> childCarrierCounts, AtomicLong parentIterations,
				AtomicLong childSpawned) {
			ParentChildStats() {
				this(new IdentityHashMap<>(), new IdentityHashMap<>(), new AtomicLong(), new AtomicLong());
			}
		}

		var stop = new AtomicBoolean();
		Map<Thread, ParentChildStats> perParent = new ConcurrentHashMap<>();
		List<Thread> parents = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			parents.add(parentFactory.newThread(() -> {
				var stats = new ParentChildStats();
				perParent.put(Thread.currentThread(), stats);
				for (long iter = 0; !stop.get(); iter++) {
					// record parent carrier
					Thread parentCarrier = currentCarrier();
					var parentCounter = stats.parentCarrierCounts.computeIfAbsent(parentCarrier, t -> new AtomicLong());
					parentCounter.lazySet(parentCounter.get() + 1);
					stats.parentIterations.lazySet(iter + 1);

					// spawn a short-lived child every 16 iterations to avoid overwhelming the
					// scheduler; join it before proceeding so no concurrent access to
					// childCarrierCounts
					if ((iter & 0xF) == 0) {
						var childThread = childFactory.newThread(() -> {
							Thread childCarrier = currentCarrier();
							var childCounter = stats.childCarrierCounts.computeIfAbsent(childCarrier,
									t -> new AtomicLong());
							childCounter.lazySet(childCounter.get() + 1);
						});
						childThread.start();
						try {
							childThread.join();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return;
						}
						stats.childSpawned.lazySet(stats.childSpawned.get() + 1);
					}

					park(strategy, iter);
				}
			}));
		}

		parents.forEach(Thread::start);
		Thread.sleep(10_000);
		stop.set(true);
		for (var parent : parents) {
			parent.join(5_000);
		}

		System.out.println("Strategy: " + strategy + ", affinity=" + affinity + " (" + n + " parent VTs)");
		int matchCount = 0;
		int reportedCount = 0;
		for (var entry : perParent.entrySet()) {
			var stats = entry.getValue();
			long parentIters = stats.parentIterations.get();
			long children = stats.childSpawned.get();

			if (stats.parentCarrierCounts.isEmpty() || stats.childCarrierCounts.isEmpty()) {
				System.out.printf("%s => insufficient data (parentIters=%d, children=%d)%n", entry.getKey().getName(),
						parentIters, children);
				continue;
			}

			var parentDominant = stats.parentCarrierCounts.entrySet().stream()
					.max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get()))).orElseThrow();
			long parentDominantCount = parentDominant.getValue().get();

			var childDominant = stats.childCarrierCounts.entrySet().stream()
					.max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get()))).orElseThrow();
			long childTotal = stats.childCarrierCounts.values().stream().mapToLong(AtomicLong::get).sum();
			long childDominantCount = childDominant.getValue().get();

			boolean dominantMatch = parentDominant.getKey() == childDominant.getKey();
			if (dominantMatch) {
				matchCount++;
			}
			reportedCount++;

			System.out.printf(
					"%s => parent dominant: %s (%d/%d = %.2f%%, %d carriers)"
							+ " | child dominant: %s (%d/%d = %.2f%%, %d carriers) | match=%b%n",
					entry.getKey().getName(), parentDominant.getKey().getName(), parentDominantCount, parentIters,
					parentIters > 0 ? 100.0 * parentDominantCount / parentIters : 0.0, stats.parentCarrierCounts.size(),
					childDominant.getKey().getName(), childDominantCount, childTotal,
					childTotal > 0 ? 100.0 * childDominantCount / childTotal : 0.0, stats.childCarrierCounts.size(),
					dominantMatch);
		}

		double matchRate = reportedCount > 0 ? 100.0 * matchCount / reportedCount : 0.0;
		System.out.printf("%nParent-child dominant carrier match: %d / %d = %.2f%%%n%n", matchCount, reportedCount,
				matchRate);
		System.out.flush();
	}

}
