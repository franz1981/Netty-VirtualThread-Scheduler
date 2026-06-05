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

class IdleCarrierTrackerTest {

	@Test
	void markIdleAndActive() {
		var tracker = new IdleCarrierTracker(4);
		assertFalse(tracker.isIdle(0));
		tracker.markIdle(0);
		assertTrue(tracker.isIdle(0));
		assertFalse(tracker.isIdle(1));
		tracker.markActive(0);
		assertFalse(tracker.isIdle(0));
	}

	@Test
	void findIdleReturnsFirstIdle() {
		var tracker = new IdleCarrierTracker(4);
		assertEquals(-1, tracker.findIdle());
		tracker.markIdle(2);
		assertEquals(2, tracker.findIdle());
		tracker.markIdle(1);
		assertEquals(1, tracker.findIdle());
	}

	@Test
	void multipleWordsForLargeCarrierCount() {
		var tracker = new IdleCarrierTracker(128);
		tracker.markIdle(65);
		assertTrue(tracker.isIdle(65));
		assertFalse(tracker.isIdle(64));
		assertEquals(65, tracker.findIdle());
		tracker.markIdle(3);
		assertEquals(3, tracker.findIdle());
	}

	@Test
	void markIdleIsIdempotent() {
		var tracker = new IdleCarrierTracker(4);
		tracker.markIdle(0);
		tracker.markIdle(0);
		assertTrue(tracker.isIdle(0));
		tracker.markActive(0);
		assertFalse(tracker.isIdle(0));
	}

	@Test
	void markActiveIsIdempotent() {
		var tracker = new IdleCarrierTracker(4);
		tracker.markActive(0);
		assertFalse(tracker.isIdle(0));
	}

	@Test
	void concurrentMarkIdleActive() throws InterruptedException {
		var tracker = new IdleCarrierTracker(16);
		int iterations = 100_000;
		var threads = new Thread[8];
		for (int t = 0; t < threads.length; t++) {
			final int carrierId = t;
			threads[t] = Thread.ofPlatform().start(() -> {
				for (int i = 0; i < iterations; i++) {
					tracker.markIdle(carrierId);
					assertTrue(tracker.isIdle(carrierId));
					tracker.markActive(carrierId);
				}
			});
		}
		for (var thread : threads) {
			thread.join();
		}
		for (int i = 0; i < 8; i++) {
			assertFalse(tracker.isIdle(i));
		}
		assertEquals(-1, tracker.findIdle());
	}
}
