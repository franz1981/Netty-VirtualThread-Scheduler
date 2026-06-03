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

import org.junit.jupiter.api.Test;

class ClusterStateTest {

	private static ClusterState create(int size) {
		var schedulers = new EventLoopScheduler[size];
		for (int i = 0; i < size; i++) {
			schedulers[i] = new EventLoopScheduler(i, Thread.ofPlatform().factory(), 16, null, null);
		}
		return new ClusterState(schedulers);
	}

	@Test
	void tryStartSearcherIsExclusive() {
		var cs = create(2);
		assertEquals(0, cs.nSearching());
		assertTrue(cs.tryStartSearcher());
		assertEquals(1, cs.nSearching());
		assertFalse(cs.tryStartSearcher());
		assertEquals(1, cs.nSearching());
	}

	@Test
	void stoppedSearchingDecrementsToZero() {
		var cs = create(2);
		assertTrue(cs.tryStartSearcher());
		cs.stoppedSearching();
		assertEquals(0, cs.nSearching());
	}

	@Test
	void tryStartAfterStoppedSucceeds() {
		var cs = create(2);
		assertTrue(cs.tryStartSearcher());
		cs.stoppedSearching();
		assertTrue(cs.tryStartSearcher());
		assertEquals(1, cs.nSearching());
		cs.stoppedSearching();
		assertEquals(0, cs.nSearching());
	}

	@Test
	void concurrentTryStartOnlyOneWins() throws InterruptedException {
		var cs = create(4);
		int iterations = 100_000;
		var threads = new Thread[4];
		var wins = new java.util.concurrent.atomic.LongAdder();
		var losses = new java.util.concurrent.atomic.LongAdder();
		var barrier = new java.util.concurrent.CyclicBarrier(threads.length);
		for (int t = 0; t < threads.length; t++) {
			threads[t] = Thread.ofPlatform().start(() -> {
				try {
					barrier.await();
				} catch (Exception e) {
					return;
				}
				for (int i = 0; i < iterations; i++) {
					if (cs.tryStartSearcher()) {
						wins.increment();
						cs.stoppedSearching();
					} else {
						losses.increment();
					}
				}
			});
		}
		for (var thread : threads) {
			thread.join();
		}
		assertEquals(0, cs.nSearching(), "nSearching must be 0 after all threads finish");
		assertTrue(wins.sum() > 0, "at least some tryStart should succeed");
		assertEquals(iterations * threads.length, wins.sum() + losses.sum());
	}
}
