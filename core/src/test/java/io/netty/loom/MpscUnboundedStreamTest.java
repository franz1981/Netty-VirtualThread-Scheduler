package io.netty.loom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MpscUnboundedStream. These are single-threaded tests to verify
 * basic functionality.
 */
class MpscUnboundedStreamTest {

	private MpscUnboundedStream<String> queue;

	@BeforeEach
	void setUp() {
		queue = new MpscUnboundedStream<>(4);
	}

	@Test
	void testOfferAndPoll() {
		assertTrue(queue.offer("item1"));
		assertTrue(queue.offer("item2"));

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
			assertTrue(queue.offer("item" + i));
		}

		for (int i = 0; i < 10; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertNull(queue.poll());
		assertTrue(queue.isEmpty());
	}

	@Test
	void testResizeBehavior() {
		// Initial capacity is 4, so adding more should trigger resize
		for (int i = 0; i < 20; i++) {
			assertTrue(queue.offer("item" + i));
		}

		assertEquals(20, queue.size());

		for (int i = 0; i < 20; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertTrue(queue.isEmpty());
	}

	@Test
	void testOfferAfterResize() {
		// Fill initial buffer and trigger resize
		for (int i = 0; i < 10; i++) {
			queue.offer("item" + i);
		}

		// Poll some elements
		for (int i = 0; i < 5; i++) {
			queue.poll();
		}

		// Offer more elements after resize
		for (int i = 10; i < 15; i++) {
			queue.offer("item" + i);
		}

		// Verify remaining elements
		for (int i = 5; i < 15; i++) {
			assertEquals("item" + i, queue.poll());
		}

		assertTrue(queue.isEmpty());
	}

	@Test
	void testClose() {
		assertFalse(queue.isClosed());

		queue.offer("item1");
		queue.close();

		assertTrue(queue.isClosed());

		// After close, offer should return false
		assertFalse(queue.offer("item2"));

		// But we can still poll existing elements
		assertEquals("item1", queue.poll());
	}

	@Test
	void testCloseOnEmptyQueue() {
		queue.close();
		assertTrue(queue.isClosed());
		assertFalse(queue.offer("item1"));
		assertNull(queue.poll());
	}

	@Test
	void testCloseMultipleTimes() {
		queue.close();
		queue.close(); // Should be idempotent
		assertTrue(queue.isClosed());
	}

	@Test
	void testSizeAfterClose() {
		queue.offer("item1");
		queue.offer("item2");
		queue.close();

		// Size should still work after close
		assertEquals(2, queue.size());

		queue.poll();
		assertEquals(1, queue.size());
	}

	@Test
	void testDrainAfterClose() {
		// Offer several elements, close, then drain while checking size and isEmpty
		for (int i = 0; i < 5; i++) {
			assertTrue(queue.offer("item" + i));
		}

		// Close the queue; offers should be rejected but existing elements remain
		queue.close();
		assertTrue(queue.isClosed());
		assertEquals(5, queue.size());
		assertFalse(queue.isEmpty());
		assertFalse(queue.offer("shouldFail"));

		// Drain and verify size decrements and isEmpty becomes true at the end
		for (int i = 0; i < 5; i++) {
			assertEquals("item" + i, queue.poll());
			assertEquals(4 - i, queue.size());
			if (i < 4) {
				assertFalse(queue.isEmpty());
			}
		}

		// Now the queue should be empty and further polls return null
		assertNull(queue.poll());
		assertTrue(queue.isEmpty());
		assertEquals(0, queue.size());

		// Offers remain rejected after close
		assertFalse(queue.offer("afterClose"));
	}

	@Test
	void testConcurrentCloseAndOffers() throws Exception {
		// Deterministic concurrent scenario:
		// - Preload two elements
		// - Start a "winning" producer that offers immediately
		// - Start a "losing" producer that only offers after we closed the queue
		// - Consumer waits for the winning producer to complete, then closes and drains
		final MpscUnboundedStream<String> q = new MpscUnboundedStream<>(4);
		q.offer("init0");
		q.offer("init1");

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch winnerDone = new CountDownLatch(1);
		final CountDownLatch closed = new CountDownLatch(1);

		final AtomicBoolean winnerSucceeded = new AtomicBoolean(false);
		final AtomicBoolean loserSucceeded = new AtomicBoolean(false);
		final AtomicReference<List<String>> drainedRef = new AtomicReference<>();

		Thread producerWinner = new Thread(() -> {
			try {
				start.await();
				boolean r = q.offer("win");
				winnerSucceeded.set(r);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} finally {
				winnerDone.countDown();
			}
		}, "producer-winner");

		Thread producerLoser = new Thread(() -> {
			try {
				start.await();
				// wait until the consumer signals it has closed the queue
				closed.await();
				boolean r = q.offer("lose");
				loserSucceeded.set(r);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}, "producer-loser");

		Thread consumer = new Thread(() -> {
			try {
				start.await();
				// Wait for the winner to attempt an offer so we deterministically close after
				// it.
				winnerDone.await(1, TimeUnit.SECONDS);
				q.close();
				closed.countDown();

				List<String> drained = new ArrayList<>();
				String s;
				while ((s = q.poll()) != null) {
					drained.add(s);
				}
				drainedRef.set(drained);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}, "consumer");

		producerWinner.start();
		producerLoser.start();
		consumer.start();

		// start the race
		start.countDown();

		// Join threads with timeout to avoid deadlocks flaking the test
		producerWinner.join(2000);
		producerLoser.join(2000);
		consumer.join(2000);

		assertTrue(winnerSucceeded.get(), "expected the winning producer to succeed before close");
		assertFalse(loserSucceeded.get(), "expected the losing producer to fail after close");

		List<String> drained = drainedRef.get();
		assertNotNull(drained, "consumer must have drained the queue");

		// Preloaded elements should be drained first, then the winning offer
		assertEquals(Arrays.asList("init0", "init1", "win"), drained);

		assertTrue(q.isClosed());
		assertTrue(q.isEmpty());
		assertEquals(0, q.size());
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
			assertTrue(queue.offer("item" + i));
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
	void testIsEmptyWithPendingElements() {
		queue.offer("item1");
		queue.offer("item2");
		queue.offer("item3");

		assertFalse(queue.isEmpty());

		queue.poll();
		assertFalse(queue.isEmpty());

		queue.poll();
		assertFalse(queue.isEmpty());

		queue.poll();
		assertTrue(queue.isEmpty());
	}

	@Test
	void testIntegerQueue() {
		MpscUnboundedStream<Integer> intQueue = new MpscUnboundedStream<>(4);

		for (int i = 0; i < 10; i++) {
			assertTrue(intQueue.offer(i));
		}

		for (int i = 0; i < 10; i++) {
			assertEquals(Integer.valueOf(i), intQueue.poll());
		}

		assertTrue(intQueue.isEmpty());
	}

	@Test
	void testSmallInitialCapacity() {
		assertThrows(IllegalArgumentException.class, () -> new MpscUnboundedStream<String>(1));
	}

	@Test
	void testAutoCloseable() throws Exception {
		try (MpscUnboundedStream<String> autoQueue = new MpscUnboundedStream<>(4)) {
			autoQueue.offer("item1");
			assertEquals("item1", autoQueue.poll());
		}
		// Queue should be closed after try-with-resources
	}

	@Test
	void testBufferTransition() {
		// Test that we correctly transition from one buffer to another
		MpscUnboundedStream<Integer> testQueue = new MpscUnboundedStream<>(2);

		// Fill first buffer
		testQueue.offer(1);
		testQueue.offer(2);

		// Trigger resize by filling more
		testQueue.offer(3);
		testQueue.offer(4);
		testQueue.offer(5);

		// Poll first buffer elements
		assertEquals(Integer.valueOf(1), testQueue.poll());
		assertEquals(Integer.valueOf(2), testQueue.poll());

		// Poll elements from new buffer
		assertEquals(Integer.valueOf(3), testQueue.poll());
		assertEquals(Integer.valueOf(4), testQueue.poll());
		assertEquals(Integer.valueOf(5), testQueue.poll());

		assertNull(testQueue.poll());
	}

	@Test
	void testSizeConsistency() {
		// Ensure size stays consistent through offer/poll cycles
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
	void testStressConcurrentProducersCloseAndDrain() throws Exception {
		final int producers = 64;
		final MpscUnboundedStream<String> q = new MpscUnboundedStream<>(8);

		// Each producer will try to add one element labeled by its id
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(producers);
		final AtomicBoolean[] succeeded = new AtomicBoolean[producers];
		for (int i = 0; i < producers; i++)
			succeeded[i] = new AtomicBoolean(false);

		for (int i = 0; i < producers; i++) {
			final int id = i;
			Thread t = new Thread(() -> {
				try {
					start.await();
					// small randomized busy spin to increase contention
					if ((id & 7) == 0)
						Thread.yield();
					boolean r = q.offer("p" + id);
					succeeded[id].set(r);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}, "stress-producer-" + i);
			t.start();
		}

		// start producers
		start.countDown();

		// Wait a tiny bit to let many offers happen concurrently, then close and drain
		Thread.sleep(30);
		q.close();

		// Wait for producers to finish (with timeout)
		done.await(5, TimeUnit.SECONDS);

		List<String> drained = new ArrayList<>();
		String s;
		while ((s = q.poll()) != null) {
			drained.add(s);
		}

		// Count successes
		for (AtomicBoolean b : succeeded)
			if (b.get())
				;

		// All successful offers must appear in the drained list
		for (int i = 0; i < producers; i++) {
			if (succeeded[i].get()) {
				assertTrue(drained.remove("p" + i), "drained must contain successful producer element p" + i);
			}
		}

		// The queue should be closed and empty after drain
		assertTrue(q.isClosed());
		assertTrue(q.isEmpty());
		assertEquals(0, q.size());
	}
}
