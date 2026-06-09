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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MpscUnboundedQueueTest {

	private MpscUnboundedQueue<String> queue;

	@BeforeEach
	void setUp() {
		queue = new MpscUnboundedQueue<>(4);
	}

	@Test
	void testOfferAndPoll() {
		queue.offer("item1");
		queue.offer("item2");

		assertEquals("item1", queue.poll());
		assertEquals("item2", queue.poll());
		assertNull(queue.poll());
	}

	@Test
	void testOfferNullThrowsException() {
		assertThrows(NullPointerException.class, () -> queue.offer(null));
	}

	@Test
	void testIsEmptyOnNewQueue() {
		assertTrue(queue.isEmpty());
		assertEquals(0, queue.size());
	}

	@Test
	void testIsEmptyAfterOfferAndPoll() {
		assertTrue(queue.isEmpty());

		queue.offer("item1");
		assertFalse(queue.isEmpty());
		assertEquals(1, queue.size());

		queue.poll();
		assertTrue(queue.isEmpty());
		assertEquals(0, queue.size());
	}

	@Test
	void testSize() {
		assertEquals(0, queue.size());

		queue.offer("item1");
		assertEquals(1, queue.size());

		queue.offer("item2");
		assertEquals(2, queue.size());

		queue.offer("item3");
		assertEquals(3, queue.size());

		queue.poll();
		assertEquals(2, queue.size());

		queue.poll();
		assertEquals(1, queue.size());

		queue.poll();
		assertEquals(0, queue.size());
	}

	@Test
	void testMultipleOfferAndPoll() {
		for (int i = 0; i < 10; i++) {
			queue.offer("item" + i);
		}

		for (int i = 0; i < 10; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertNull(queue.poll());
		assertTrue(queue.isEmpty());
	}

	@Test
	void testResizeBehavior() {
		for (int i = 0; i < 20; i++) {
			queue.offer("item" + i);
		}

		assertEquals(20, queue.size());

		for (int i = 0; i < 20; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertTrue(queue.isEmpty());
	}

	@Test
	void testOfferAfterResize() {
		for (int i = 0; i < 10; i++) {
			queue.offer("item" + i);
		}

		for (int i = 0; i < 5; i++) {
			queue.poll();
		}

		for (int i = 10; i < 15; i++) {
			queue.offer("item" + i);
		}

		for (int i = 5; i < 15; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertTrue(queue.isEmpty());
	}

	@Test
	void testAlternatingOfferAndPoll() {
		for (int i = 0; i < 20; i++) {
			queue.offer("item" + i);
			assertEquals("item" + i, queue.poll());
			assertTrue(queue.isEmpty());
		}
	}

	@Test
	void testLargeNumberOfElements() {
		int count = 1000;

		for (int i = 0; i < count; i++) {
			queue.offer("item" + i);
		}

		assertEquals(count, queue.size());

		for (int i = 0; i < count; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertTrue(queue.isEmpty());
	}

	@Test
	void testPollOnEmptyQueue() {
		assertNull(queue.poll());
		assertNull(queue.poll());
		assertTrue(queue.isEmpty());
	}

	@Test
	void testIntegerQueue() {
		MpscUnboundedQueue<Integer> intQueue = new MpscUnboundedQueue<>(4);

		for (int i = 0; i < 10; i++) {
			intQueue.offer(i);
		}

		for (int i = 0; i < 10; i++) {
			assertEquals(Integer.valueOf(i), intQueue.poll());
		}

		assertTrue(intQueue.isEmpty());
	}

	@Test
	void testSmallInitialCapacity() {
		assertThrows(IllegalArgumentException.class, () -> new MpscUnboundedQueue<String>(1));
	}

	@Test
	void testBufferTransition() {
		MpscUnboundedQueue<Integer> testQueue = new MpscUnboundedQueue<>(2);

		testQueue.offer(1);
		testQueue.offer(2);
		testQueue.offer(3);
		testQueue.offer(4);
		testQueue.offer(5);

		assertEquals(Integer.valueOf(1), testQueue.poll());
		assertEquals(Integer.valueOf(2), testQueue.poll());
		assertEquals(Integer.valueOf(3), testQueue.poll());
		assertEquals(Integer.valueOf(4), testQueue.poll());
		assertEquals(Integer.valueOf(5), testQueue.poll());

		assertNull(testQueue.poll());
	}

	@Test
	void testSizeConsistency() {
		for (int cycle = 0; cycle < 5; cycle++) {
			for (int i = 0; i < 10; i++) {
				queue.offer("item" + i);
				assertEquals(i + 1, queue.size());
			}

			for (int i = 0; i < 10; i++) {
				queue.poll();
				assertEquals(9 - i, queue.size());
			}

			assertEquals(0, queue.size());
			assertTrue(queue.isEmpty());
		}
	}

	@Test
	void testConcurrentProducers() throws Exception {
		final int producers = 64;
		final int itemsPerProducer = 100;
		final MpscUnboundedQueue<String> q = new MpscUnboundedQueue<>(8);

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(producers);

		for (int i = 0; i < producers; i++) {
			final int id = i;
			Thread t = new Thread(() -> {
				try {
					start.await();
					for (int j = 0; j < itemsPerProducer; j++) {
						q.offer("p" + id + "-" + j);
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}, "producer-" + i);
			t.start();
		}

		start.countDown();
		done.await(5, TimeUnit.SECONDS);

		List<String> drained = new ArrayList<>();
		String s;
		while ((s = q.poll()) != null) {
			drained.add(s);
		}

		assertEquals(producers * itemsPerProducer, drained.size());
		assertTrue(q.isEmpty());
		assertEquals(0, q.size());

		// verify each producer's items appear in order
		int[] nextExpected = new int[producers];
		for (String item : drained) {
			int dash1 = item.indexOf('-');
			int producerId = Integer.parseInt(item.substring(1, dash1));
			int seqNo = Integer.parseInt(item.substring(dash1 + 1));
			assertEquals(nextExpected[producerId], seqNo, "Producer " + producerId + " items must be in FIFO order");
			nextExpected[producerId]++;
		}
	}
}
