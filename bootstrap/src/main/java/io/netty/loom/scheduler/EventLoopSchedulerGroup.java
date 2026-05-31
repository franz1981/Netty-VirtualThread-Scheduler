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
package io.netty.loom.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.ServiceLoader;

/**
 * Global pool of carrier threads, each running an {@link EventLoopScheduler}.
 * Created eagerly by {@link NettyScheduler} at JVM startup and never shut down.
 *
 * <p>
 * Size defaults to {@code availableProcessors()} and can be overridden with
 * {@code -Dio.netty.loom.schedulers=N}.
 */
public class EventLoopSchedulerGroup {

	private static final int DEFAULT_SIZE = Integer.getInteger("io.netty.loom.schedulers",
			Runtime.getRuntime().availableProcessors());
	private static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer
			.getInteger("io.netty.loom.resumed.continuations", 1024);

	private final EventLoopScheduler[] schedulers;
	private final EventLoopScheduler[][] clusters;
	private final ClusterState[] clusterStates;

	EventLoopSchedulerGroup(NettyScheduler scheduler) {
		this(DEFAULT_SIZE, scheduler);
	}

	private EventLoopSchedulerGroup(int size, NettyScheduler scheduler) {
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0");
		}
		var topology = loadTopology();
		var carrierThreadFactory = Thread.ofPlatform().daemon(true).factory();
		schedulers = new EventLoopScheduler[size];
		for (int i = 0; i < size; i++) {
			final int idx = i;
			Runnable onStart = (topology != null) ? () -> {
				topology.bindCarrier(idx, size, Thread.currentThread());
				Thread.currentThread()
						.setName("carrier-" + idx + "-cluster" + topology.cluster(idx) + "-core" + topology.core(idx));
			} : null;
			schedulers[i] = new EventLoopScheduler(i, carrierThreadFactory, RESUMED_CONTINUATIONS_EXPECTED_COUNT,
					scheduler, onStart);
		}
		if (topology != null) {
			var clusterMap = new LinkedHashMap<Integer, ArrayList<EventLoopScheduler>>();
			for (int i = 0; i < size; i++) {
				clusterMap.computeIfAbsent(topology.cluster(i), k -> new ArrayList<>()).add(schedulers[i]);
			}
			clusters = clusterMap.values().stream().map(l -> l.toArray(new EventLoopScheduler[0]))
					.toArray(EventLoopScheduler[][]::new);
		} else {
			clusters = new EventLoopScheduler[][]{schedulers.clone()};
		}

		var scope = topology != null ? topology.stealScope() : CarrierTopology.StealScope.GLOBAL;
		if (scope == CarrierTopology.StealScope.GLOBAL) {
			var global = new ClusterState(schedulers);
			this.clusterStates = new ClusterState[]{global};
			for (int i = 0; i < size; i++) {
				schedulers[i].clusterState = global;
			}
		} else {
			this.clusterStates = new ClusterState[clusters.length];
			for (int c = 0; c < clusters.length; c++) {
				clusterStates[c] = new ClusterState(clusters[c]);
				for (var carrier : clusters[c]) {
					carrier.clusterState = clusterStates[c];
				}
			}
		}
		for (int i = 0; i < size; i++) {
			schedulers[i].group = this;
		}

		if (EventLoopScheduler.WORK_STEALING_ENABLED && size > 1) {
			for (int i = 0; i < size; i++) {
				var allowed = new ArrayList<EventLoopScheduler>();
				for (int j = 0; j < size; j++) {
					if (j != i && isAllowedPeer(i, j, scope, topology)) {
						allowed.add(schedulers[j]);
					}
				}
				if (!allowed.isEmpty()) {
					schedulers[i].setSiblings(allowed.toArray(new EventLoopScheduler[0]));
				}
			}
		}
	}

	private static boolean isAllowedPeer(int self, int other, CarrierTopology.StealScope scope,
			CarrierTopology topology) {
		return switch (scope) {
			case GLOBAL -> true;
			case CLUSTER_LOCAL -> topology.cluster(self) == topology.cluster(other);
			case SMT_LOCAL -> topology.core(self) == topology.core(other);
		};
	}

	private static CarrierTopology loadTopology() {
		String className = System.getProperty("io.netty.loom.topology");
		if (className != null) {
			try {
				return (CarrierTopology) Class.forName(className).getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				throw new RuntimeException("Failed to load carrier topology: " + className, e);
			}
		}
		return ServiceLoader.load(CarrierTopology.class).findFirst().orElse(null);
	}

	/**
	 * Returns the global singleton. Shortcut for {@link NettyScheduler#group()}.
	 */
	public static EventLoopSchedulerGroup instance() {
		return NettyScheduler.group();
	}

	/** Returns the number of carriers in the pool. */
	public int size() {
		return schedulers.length;
	}

	/** Returns the scheduler for the carrier at the given index. */
	public EventLoopScheduler scheduler(int index) {
		return schedulers[index];
	}

	/** Returns the number of clusters. */
	public int clusterCount() {
		return clusters.length;
	}

	/** Returns the schedulers in the given cluster. */
	public EventLoopScheduler[] cluster(int clusterIndex) {
		return clusters[clusterIndex];
	}

	/**
	 * Returns any idle carrier's ID, or -1 if none are idle.
	 */
	public int idleCarrierId() {
		for (var cs : clusterStates) {
			int idle = cs.findIdle();
			if (idle >= 0) {
				return idle;
			}
		}
		return -1;
	}

	/**
	 * Returns a thread factory for external submissions. Sticks to a carrier —
	 * concentrating bursts like FJP's per-thread probe. Prefers the current cluster
	 * when re-targeting.
	 */
	public java.util.concurrent.ThreadFactory virtualThreadFactory() {
		var current = new int[]{-1};
		return runnable -> {
			int idx = current[0];
			if (idx >= 0 && !schedulers[idx].clusterState.idleTracker().isIdle(idx)) {
				return schedulers[idx].virtualThreadFactory().newThread(runnable);
			}
			int idle = findIdlePreferCluster(idx);
			if (idle >= 0) {
				idx = idle;
			} else if (idx < 0) {
				idx = 0;
			}
			current[0] = idx;
			return schedulers[idx].virtualThreadFactory().newThread(runnable);
		};
	}

	private int findIdlePreferCluster(int currentIdx) {
		if (currentIdx >= 0) {
			int idle = schedulers[currentIdx].clusterState.findIdle();
			if (idle >= 0) {
				return idle;
			}
		}
		for (var cs : clusterStates) {
			int idle = cs.findIdle();
			if (idle >= 0) {
				return idle;
			}
		}
		return -1;
	}

	/**
	 * Returns {@code count} schedulers that have no registered pinned poller, or
	 * {@code null} if not enough are available.
	 */
	public EventLoopScheduler[] availableSchedulers(int count) {
		var result = new EventLoopScheduler[count];
		int found = 0;
		for (int i = 0; i < schedulers.length && found < count; i++) {
			if (!schedulers[i].hasRegisteredPinnedPoller()) {
				result[found++] = schedulers[i];
			}
		}
		return found == count ? result : null;
	}
}
