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

import io.netty.loom.scheduler.CarrierTopology.StealScope;
import org.junit.jupiter.api.Test;

class CarrierTopologyTest {

	@Test
	void defaultTopologyIsGlobalSingleCluster() {
		var topology = new CarrierTopology() {
		};
		assertEquals(0, topology.cluster(0));
		assertEquals(0, topology.cluster(99));
		assertEquals(StealScope.GLOBAL, topology.stealScope());
	}

	@Test
	void defaultCoreIsPerCarrier() {
		var topology = new CarrierTopology() {
		};
		assertNotEquals(topology.core(0), topology.core(1));
		assertEquals(0, topology.core(0));
		assertEquals(1, topology.core(1));
	}

	@Test
	void stealScopeOrdering() {
		assertTrue(StealScope.SMT_LOCAL.ordinal() > StealScope.CLUSTER_LOCAL.ordinal());
		assertTrue(StealScope.CLUSTER_LOCAL.ordinal() > StealScope.GLOBAL.ordinal());
	}
}
