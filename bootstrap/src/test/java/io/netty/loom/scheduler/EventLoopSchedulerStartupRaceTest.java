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
	void parkedCarrierStartedBeforeClusterStateMustBeDiscoverableAfterClusterStateIsConnected() throws Exception {
		var carrierAtStartupHook = new CountDownLatch(1);
		var releaseCarrier = new CountDownLatch(1);
		var carrierLeftStartupHook = new CountDownLatch(1);
		var victimAtStartupHook = new CountDownLatch(1);
		var releaseVictim = new CountDownLatch(1);

		var scheduler = new EventLoopScheduler(0, Thread.ofPlatform().daemon(true).factory(), 16, null, () -> {
			carrierAtStartupHook.countDown();
			await(releaseCarrier);
			carrierLeftStartupHook.countDown();
		});
		var victim = new EventLoopScheduler(1, Thread.ofPlatform().daemon(true).factory(), 16, null, () -> {
			victimAtStartupHook.countDown();
			await(releaseVictim);
		});

		ClusterState clusterState = null;
		boolean searchStarted = false;
		try {
			assertTrue(carrierAtStartupHook.await(5, TimeUnit.SECONDS), "carrier did not reach startup hook");
			assertTrue(victimAtStartupHook.await(5, TimeUnit.SECONDS), "victim did not reach startup hook");
			assertNull(scheduler.clusterState, "test must release the carrier before ClusterState is connected");

			releaseCarrier.countDown();
			assertTrue(carrierLeftStartupHook.await(5, TimeUnit.SECONDS), "carrier did not leave startup hook");
			awaitCarrierWaiting(scheduler);

			clusterState = new ClusterState(new EventLoopScheduler[]{scheduler, victim});
			scheduler.clusterState = clusterState;
			victim.clusterState = clusterState;

			searchStarted = clusterState.tryStartSearcher();
			assertTrue(searchStarted, "test setup should be able to start one searcher");
			assertTrue(clusterState.wakeFirstIdle(victim),
					"carrier is actually PARKED and connected to ClusterState, so it must be discoverable as idle");
		} finally {
			if (searchStarted && clusterState != null && clusterState.nSearching() > 0) {
				clusterState.stoppedSearching();
			}
			releaseCarrier.countDown();
			releaseVictim.countDown();
			scheduler.wakeup();
			victim.wakeup();
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
