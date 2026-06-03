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
package io.netty.loom.topology;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.loom.scheduler.CarrierTopology;
import io.netty.loom.scheduler.EventLoopScheduler;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LinuxCarrierTopologyTest {

	@Test
	void fakeClusterTopologyGroupsCorrectly() {
		var topology = new FakeClusterTopology();
		assertEquals(topology.cluster(0), topology.cluster(1));
		assertEquals(topology.cluster(2), topology.cluster(3));
		assertNotEquals(topology.cluster(0), topology.cluster(2));
		assertEquals(CarrierTopology.StealScope.CLUSTER_LOCAL, topology.stealScope());
	}

	@Test
	void defaultTopologyIsGlobal() {
		var topology = new CarrierTopology() {
		};
		assertEquals(0, topology.cluster(0));
		assertEquals(CarrierTopology.StealScope.GLOBAL, topology.stealScope());
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void pinAndVerifyAffinity() {
		var original = LinuxCarrierTopology.getAffinity();
		assumeTrue(original != null, "sched_getaffinity not available");
		try {
			LinuxCarrierTopology.setAffinity(0);
			assertEquals(0, LinuxCarrierTopology.pinnedCpu(), "thread should be pinned to CPU 0");
		} finally {
			LinuxCarrierTopology.setAffinityMask(original);
		}
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void readTopologyProducesConsistentClusters() {
		var topology = new LinuxCarrierTopology();
		int count = Runtime.getRuntime().availableProcessors();
		assumeTrue(count >= 2, "need at least 2 CPUs");
		int cluster0 = topology.cluster(0);
		assertTrue(cluster0 >= 0, "cluster ID should be non-negative for valid CPUs");
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void outOfRangeCarrierGetsUniqueCluster() {
		var topology = new LinuxCarrierTopology();
		int outOfRange = Runtime.getRuntime().availableProcessors() + 10;
		assertNotEquals(topology.cluster(0), topology.cluster(outOfRange),
				"out-of-range carrier should not share a cluster with valid CPUs");
	}

	@Test
	@Timeout(15)
	@EnabledIfSystemProperty(named = "io.netty.loom.workstealing.enabled", matches = "true")
	void stealingRespectsClusterTopology() throws Exception {
		// FakeClusterTopology via system property: [0,1]=cluster A, [2,3]=cluster B,
		// clusterLocal=true
		var group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 4, "need at least 4 carriers");
		var scheduler0 = group.scheduler(0);
		var scheduler1 = group.scheduler(1);

		var blockerA = new AtomicBoolean(true);
		var blockerB = new AtomicBoolean(true);
		var startedA = new CountDownLatch(1);
		var startedB = new CountDownLatch(1);
		var runningRef = new CompletableFuture<EventLoopScheduler>();

		scheduler0.virtualThreadFactory().newThread(() -> {
			startedA.countDown();
			while (blockerA.get()) {
				Thread.onSpinWait();
			}
		}).start();
		scheduler1.virtualThreadFactory().newThread(() -> {
			startedB.countDown();
			while (blockerB.get()) {
				Thread.onSpinWait();
			}
		}).start();

		assertTrue(startedA.await(2, TimeUnit.SECONDS));
		assertTrue(startedB.await(2, TimeUnit.SECONDS));

		try {
			awaitStealOpportunity();

			scheduler0.virtualThreadFactory().newThread(() -> {
				runningRef.complete(EventLoopScheduler.currentRunningScheduler());
			}).start();

			blockerB.set(false);

			var running = runningRef.get(5, TimeUnit.SECONDS);
			assertSame(scheduler1, running,
					"VT should be stolen by cluster-local carrier 1, not cross-cluster carriers 2/3");
		} finally {
			blockerA.set(false);
			blockerB.set(false);
		}
	}

	private static void awaitStealOpportunity() throws InterruptedException {
		Thread.sleep(50);
	}
}
