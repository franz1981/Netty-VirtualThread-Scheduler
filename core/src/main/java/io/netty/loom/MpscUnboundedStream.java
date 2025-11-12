package io.netty.loom;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A Multi-Producer Single-Consumer (MPSC) unbounded array queue implementation.
 * This queue uses VarHandle for memory operations and stores the CLOSED state
 * in the MSB of the producer sequence.
 * <p>
 * Based on JCTools MpscUnboundedArrayQueue but simplified:
 * <ul>
 * <li>No Unsafe usage</li>
 * <li>No padding fields</li>
 * <li>No capacity limits (truly unbounded)</li>
 * <li>Uses VarHandle for atomic operations</li>
 * <li>MSB of producer sequence stores CLOSED information</li>
 * <li>Real producer index = (sequence &amp; ~CLOSED_BIT) &gt;&gt; 1</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class MpscUnboundedStream<E> implements AutoCloseable {

    private static final VarHandle PRODUCER_INDEX;
    private static final VarHandle CONSUMER_INDEX;
    private static final VarHandle PRODUCER_LIMIT;
    private static final VarHandle ARRAY;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PRODUCER_INDEX = lookup.findVarHandle(MpscUnboundedStream.class, "producerIndex", long.class);
            CONSUMER_INDEX = lookup.findVarHandle(MpscUnboundedStream.class, "consumerIndex", long.class);
            PRODUCER_LIMIT = lookup.findVarHandle(MpscUnboundedStream.class, "producerLimit", long.class);
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Sentinel object used to mark a jump to the next buffer
    private static final Object JUMP = new Object();
    // Marker to indicate buffer has been consumed and can be GC'd
    private static final Object BUFFER_CONSUMED = new Object();

    // MSB is used to store the CLOSED flag
    private static final long CLOSED_BIT = 1L << 63;
    // LSB is used during resize (bit 0)
    private static final long RESIZE_BIT = 1L;

    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private volatile long producerIndex;
    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private volatile long consumerIndex;
    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private volatile long producerLimit;

    private long producerMask;
    private volatile Object[] producerBuffer;
    private Object[] consumerBuffer;

    public MpscUnboundedStream(int initialCapacity) {
        // Round up to next power of 2
        int capacity = roundToPowerOfTwo(initialCapacity);
        this.producerMask = capacity - 1;
        this.producerBuffer = new Object[capacity];
        this.consumerBuffer = this.producerBuffer;
        this.producerIndex = 0;
        this.consumerIndex = 0;
        // Initialize producer limit to capacity * 2 (since we increment by 2)
        this.producerLimit = capacity * 2L;
    }

    private static int roundToPowerOfTwo(int value) {
        if (value <= 1) return 2;
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    /**
     * Offers an element to the queue. This method can be called by multiple producers.
     *
     * @param element the element to add
     * @return true if the element was added, false if the queue is closed
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not allowed");
        }

        long mask;
        Object[] buffer;
        long pIndex;

        while (true) {
            long producerLimit = (long) PRODUCER_LIMIT.getVolatile(this);
            pIndex = (long) PRODUCER_INDEX.getVolatile(this);

            // Check if queue is closed (MSB set)
            if ((pIndex & CLOSED_BIT) != 0) {
                return false;
            }

            // Lower bit is indicative of resize, if we see it we spin until it's cleared
            if ((pIndex & RESIZE_BIT) == 1) {
                continue;
            }

            // pIndex is even (lower bit is 0) -> actual index is (pIndex >> 1)
            // mask/buffer may get changed by resizing -> capture before CAS
            // A successful CAS ties the ordering: lv(pIndex) - [mask/buffer] -> cas(pIndex)
            mask = this.producerMask;
            buffer = this.producerBuffer;

            // Assumption: queue is almost always empty or near empty
            // Check producer limit before expensive operations
            if (producerLimit <= pIndex) {
                int result = offerSlowPath(buffer, pIndex, producerLimit, element);
                if (result != 0) { // 0 = CONTINUE_TO_P_INDEX_CAS
                    return result > 0; // QUEUE_RESIZE returns true
                }
            }

            // Try to claim this index by incrementing producer index by 2
            // (we use increments of 2 to have space for the resize bit)
            long nextPIndex = pIndex + 2;
            if (PRODUCER_INDEX.compareAndSet(this, pIndex, nextPIndex)) {
                break;
            }
        }

        // INDEX visible before ELEMENT
        // Calculate offset: (pIndex >> 1) & mask, but need to account for CLOSED_BIT
        final long offset = ((pIndex & ~CLOSED_BIT) >> 1) & mask;
        ARRAY.setRelease(buffer, (int) offset, element); // release element
        return true;
    }

    // Return values
    private static final int CONTINUE_TO_P_INDEX_CAS = 0;
    private static final int RETRY = -1;
    private static final int QUEUE_RESIZE = 1;

    /**
     * We do not inline resize into offer because we do not resize on fill.
     */
    private int offerSlowPath(Object[] buffer, long pIndex, long producerLimit, E element) {
        long cIndex = (long) CONSUMER_INDEX.getVolatile(this);
        long bufferCapacity = buffer.length;

        // Calculate actual producer and consumer indices
        long actualPIndex = (pIndex & ~CLOSED_BIT) >> 1;

        // Check if we have capacity with current consumer position
        if (cIndex + bufferCapacity > actualPIndex) {
            // We have capacity, update producer limit
            long newLimit = (cIndex + bufferCapacity) * 2; // multiply by 2 since we use pIndex * 2
            if (!PRODUCER_LIMIT.compareAndSet(this, producerLimit, newLimit)) {
                // CAS failed, retry from top
                return RETRY;
            } else {
                // Continue to pIndex CAS
                return CONTINUE_TO_P_INDEX_CAS;
            }
        }
        // Need to resize (unbounded queue, so we always resize when full)
        else if (PRODUCER_INDEX.compareAndSet(this, pIndex, pIndex + 1)) {
            // Successfully set resize bit, trigger resize
            resize(buffer, pIndex, element);
            return QUEUE_RESIZE;
        } else {
            // Failed to set resize bit, retry from top
            return RETRY;
        }
    }

    private void resize(Object[] oldBuffer, long pIndex, E element) {
        // At this point, pIndex has resize bit set (LSB = 1)
        int oldCapacity = oldBuffer.length;
        int newCapacity = oldCapacity * 2;
        Object[] newBuffer = new Object[newCapacity];

        // Update producer mask and buffer
        this.producerMask = newCapacity - 1;
        this.producerBuffer = newBuffer;

        // Calculate where to put the element in the new buffer
        // Only strip CLOSED_BIT, the shift handles RESIZE_BIT
        long actualIndex = (pIndex & ~CLOSED_BIT) >> 1;
        long newOffset = actualIndex & (newCapacity - 1);
        ARRAY.setRelease(newBuffer, (int) newOffset, element);

        // Link old buffer to new buffer using JUMP
        long linkOffset = oldCapacity - 1; // Last slot in old buffer
        ARRAY.setRelease(oldBuffer, (int) linkOffset, newBuffer);
        ARRAY.setRelease(oldBuffer, (int) linkOffset, JUMP);

        // Update producer limit to new capacity
        long cIndex = (long) CONSUMER_INDEX.getVolatile(this);
        long newLimit = (cIndex + newCapacity) * 2; // multiply by 2 since we use pIndex * 2
        PRODUCER_LIMIT.setVolatile(this, newLimit);

        // Clear resize bit and advance index by 2
        long newPIndex = (pIndex & ~RESIZE_BIT) + 2;
        PRODUCER_INDEX.setVolatile(this, newPIndex);
    }

    /**
     * Polls an element from the queue. This method should only be called by a single consumer.
     *
     * @return the element at the head of the queue, or null if empty
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        long cIndex = this.consumerIndex;
        Object[] buffer = this.consumerBuffer;
        long mask = buffer.length - 1;
        long offset = cIndex & mask;

        Object element = ARRAY.getAcquire(buffer, (int) offset);

        if (element == null) {
            // Queue is empty
            return null;
        }

        if (element == JUMP) {
            // Need to jump to the next buffer
            return pollNextBuffer(buffer, cIndex, mask);
        }

        // Clear the slot
        ARRAY.setRelease(buffer, (int) offset, null);

        // Update consumer index
        CONSUMER_INDEX.setRelease(this, cIndex + 1);

        return (E) element;
    }

    @SuppressWarnings("unchecked")
    private E pollNextBuffer(Object[] buffer, long cIndex, long mask) {
        // Read the next buffer reference from the last slot
        long linkOffset = mask; // Last slot (mask is length - 1)
        Object[] nextBuffer = (Object[]) ARRAY.getAcquire(buffer, (int) linkOffset);

        if (nextBuffer == null) {
            // Next buffer not ready yet (should not happen with proper JUMP logic)
            return null;
        }

        // Update consumer buffer and mask
        this.consumerBuffer = nextBuffer;
        long newMask = nextBuffer.length - 1;

        // Mark the link slot as consumed to allow GC of old buffer
        ARRAY.setRelease(buffer, (int) linkOffset, BUFFER_CONSUMED);

        // Read element from new buffer at same consumer index
        long offset = cIndex & newMask;
        Object element = ARRAY.getAcquire(nextBuffer, (int) offset);

        if (element == null) {
            return null;
        }

        // Clear the slot
        ARRAY.setRelease(nextBuffer, (int) offset, null);

        // Update consumer index
        CONSUMER_INDEX.setRelease(this, cIndex + 1);

        return (E) element;
    }

    /**
     * Returns the current size of the queue.
     * Uses a stability loop similar to JCTools to ensure consistent reads
     * of producer and consumer indices in the face of concurrent modifications.
     *
     * @return the approximate number of elements in the queue
     */
    public int size() {
        long cIndexBefore, cIndexAfter;
        long pIndex;

        // Loop until we get a stable read of consumer index
        // (i.e., consumer index doesn't change while we read producer index)
        do {
            cIndexBefore = (long) CONSUMER_INDEX.getVolatile(this);
            pIndex = (long) PRODUCER_INDEX.getVolatile(this);
            cIndexAfter = (long) CONSUMER_INDEX.getVolatile(this);
        } while (cIndexBefore != cIndexAfter);

        // Only strip MSB (CLOSED_BIT), LSB is handled by the shift
        long realProducerIndex = (pIndex & ~CLOSED_BIT) >> 1;

        long size = realProducerIndex - cIndexAfter;

        // Protect against overflow
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) size;
    }

    /**
     * Checks if the queue is empty.
     * Order matters! Loading consumer before producer allows for producer increments after consumer index is read.
     * This ensures this method is conservative in its estimate.
     *
     * @return true if the queue appears to be empty
     */
    public boolean isEmpty() {
        // Order matters: consumer before producer
        long cIndex = (long) CONSUMER_INDEX.getVolatile(this);
        long pIndex = (long) PRODUCER_INDEX.getVolatile(this);

        // Only strip MSB (CLOSED_BIT), LSB is handled by the shift
        // (cIndex - pIndex) / 2 == 0
        return ((cIndex - ((pIndex & ~CLOSED_BIT) >> 1)) == 0);
    }

    /**
     * Marks the queue as closed. After this, no more elements can be offered.
     * This is done by setting the MSB of the producer index.
     */
    @Override
    public void close() {
        long pIndex;
        while (true) {
            pIndex = (long) PRODUCER_INDEX.getVolatile(this);

            // Check if already closed
            if ((pIndex & CLOSED_BIT) != 0) {
                return;
            }

            // Set the CLOSED bit
            long closedPIndex = pIndex | CLOSED_BIT;
            if (PRODUCER_INDEX.compareAndSet(this, pIndex, closedPIndex)) {
                return;
            }
        }
    }

    /**
     * Checks if the queue is closed.
     *
     * @return true if the queue is closed
     */
    public boolean isClosed() {
        long pIndex = (long) PRODUCER_INDEX.getVolatile(this);
        return (pIndex & CLOSED_BIT) != 0;
    }
}

