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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EventLoopSchedulerStartupRaceTest {

	@Test
	@Timeout(10)
	void carrierStartedAfterClusterStateIsConnectedMustBeDiscoverableAsIdle() throws Exception {
		var supportGroup = new EventLoopSchedulerGroup(null);
		var carrierAtStartupHook = new CountDownLatch(1);
		var releaseCarrier = new CountDownLatch(1);
		var carrierLeftStartupHook = new CountDownLatch(1);

		var scheduler = new EventLoopScheduler(0, Thread.ofPlatform().daemon(true).factory(), 16, null, () -> {
			carrierAtStartupHook.countDown();
			await(releaseCarrier);
			carrierLeftStartupHook.countDown();
		});

		ClusterState clusterState = null;
		boolean searchStarted = false;
		boolean searchWakeIssued = false;
		try {
			assertEquals(Thread.State.NEW, scheduler.carrierThread().getState(),
					"carrier must not start before ClusterState is connected");

			clusterState = new ClusterState(new EventLoopScheduler[]{scheduler});
			scheduler.group = supportGroup;
			scheduler.clusterState = clusterState;

			scheduler.startCarrier();
			assertTrue(carrierAtStartupHook.await(5, TimeUnit.SECONDS), "carrier did not reach startup hook");
			releaseCarrier.countDown();
			assertTrue(carrierLeftStartupHook.await(5, TimeUnit.SECONDS), "carrier did not leave startup hook");
			awaitCarrierWaiting(scheduler);

			searchStarted = clusterState.tryStartSearcher();
			assertTrue(searchStarted, "test setup should be able to start one searcher");
			searchWakeIssued = clusterState.wakeFirstIdle(supportGroup.scheduler(0));
			assertTrue(searchWakeIssued,
					"carrier is actually PARKED and connected to ClusterState, so it must be discoverable as idle");
		} finally {
			if (searchStarted && !searchWakeIssued && clusterState != null && clusterState.nSearching() > 0) {
				clusterState.stoppedSearching();
			}
			releaseCarrier.countDown();
			scheduler.wakeup();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static void awaitCarrierWaiting(EventLoopScheduler scheduler) {
		var carrier = scheduler.carrierThread();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (carrier.getState() == Thread.State.WAITING && LockSupport.getBlocker(carrier) == null) {
				return;
			}
			Thread.onSpinWait();
		}
		fail("carrier did not enter WAITING state after startup; actual state=" + carrier.getState());
	}
}
