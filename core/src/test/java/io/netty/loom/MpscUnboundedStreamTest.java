package io.netty.loom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MpscUnboundedStream.
 * These are single-threaded tests to verify basic functionality.
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
        MpscUnboundedStream<String> smallQueue = new MpscUnboundedStream<>(1);
        
        // Should round up to power of 2 (minimum 2)
        for (int i = 0; i < 10; i++) {
            assertTrue(smallQueue.offer("item" + i));
        }
        
        assertEquals(10, smallQueue.size());
        
        for (int i = 0; i < 10; i++) {
            assertEquals("item" + i, smallQueue.poll());
        }
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
}

