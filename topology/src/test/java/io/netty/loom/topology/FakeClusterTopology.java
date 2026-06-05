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

import io.netty.loom.scheduler.CarrierTopology;

/**
 * Test topology: 2 carriers per cluster, cluster-local stealing. Loaded via
 * {@code -Dio.netty.loom.topology=io.netty.loom.topology.FakeClusterTopology}
 * in the topology module's surefire WS execution.
 */
public class FakeClusterTopology implements CarrierTopology {

	static final int CLUSTER_SIZE = 2;

	@Override
	public int cluster(int carrierIndex) {
		return carrierIndex / CLUSTER_SIZE;
	}

	@Override
	public StealScope stealScope() {
		return StealScope.CLUSTER_LOCAL;
	}
}
