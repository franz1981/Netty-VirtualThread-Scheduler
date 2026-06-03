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

/**
 * Carrier thread placement and topology-aware work stealing.
 *
 * <p>
 * Activate via {@code -Dio.netty.loom.topology=<fully-qualified-classname>}.
 * The class must have a public no-arg constructor and be visible to the system
 * classloader. If not set, carriers float and stealing includes all siblings.
 *
 * <p>
 * The scheduler builds a flat siblings array per carrier, filtered by the
 * {@link #stealScope()} strategy. The steal algorithm (power-of-two-choices) is
 * unchanged — topology only controls who participates.
 */
public interface CarrierTopology {

	/**
	 * Steal scope strategies. Each restricts the siblings array to carriers sharing
	 * a topological boundary.
	 */
	enum StealScope {
		/** Steal from any carrier. */
		GLOBAL,
		/** Steal only from carriers sharing the same LLC cluster. */
		CLUSTER_LOCAL
	}

	/**
	 * Called from the carrier thread before it starts scheduling. Pin the thread to
	 * its target CPU(s) here.
	 */
	default void bindCarrier(int carrierIndex, int carrierCount, Thread carrier) {
	}

	/**
	 * Returns the LLC cluster ID for the given carrier. Carriers in the same
	 * cluster share a last-level cache. Used when
	 * {@code stealScope() == CLUSTER_LOCAL}.
	 *
	 * <p>
	 * Default: all carriers in cluster 0 (single cluster).
	 */
	default int cluster(int carrierIndex) {
		return 0;
	}

	/**
	 * Returns the physical core ID for the given carrier. Used in carrier thread
	 * naming ({@code carrier-N-clusterX-coreY}).
	 *
	 * <p>
	 * Default: each carrier on its own core.
	 */
	default int core(int carrierIndex) {
		return carrierIndex;
	}

	/**
	 * Which topological boundary restricts work stealing.
	 *
	 * <p>
	 * Default: {@link StealScope#GLOBAL} (all carriers are eligible steal targets).
	 */
	default StealScope stealScope() {
		return StealScope.GLOBAL;
	}
}
