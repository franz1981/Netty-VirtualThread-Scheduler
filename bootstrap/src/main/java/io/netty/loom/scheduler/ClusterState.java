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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Per-cluster signaling and idle tracking state. Each cluster has its own
 * {@code nSearching} counter and {@link IdleCarrierTracker}, so clusters signal
 * and wake independently — no cross-cluster interference.
 */
final class ClusterState {

	private static final VarHandle N_SEARCHING;
	static {
		try {
			N_SEARCHING = MethodHandles.lookup().findVarHandle(ClusterState.class, "nSearching", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final EventLoopScheduler[] members;
	private final IdleCarrierTracker idleTracker;
	@SuppressWarnings("unused")
	private volatile int nSearching;

	ClusterState(EventLoopScheduler[] members) {
		this.members = members;
		int maxId = 0;
		for (var m : members) {
			maxId = Math.max(maxId, m.id());
		}
		this.idleTracker = new IdleCarrierTracker(maxId + 1);
	}

	boolean tryStartSearcher() {
		return N_SEARCHING.compareAndSet(this, 0, 1);
	}

	void stoppedSearching() {
		N_SEARCHING.getAndAdd(this, -1);
	}

	IdleCarrierTracker idleTracker() {
		return idleTracker;
	}

	EventLoopScheduler[] members() {
		return members;
	}

	int findIdle() {
		return idleTracker.findIdle();
	}
}
