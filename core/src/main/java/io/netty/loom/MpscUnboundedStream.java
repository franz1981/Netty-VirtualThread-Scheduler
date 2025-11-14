package io.netty.loom;

import io.netty.util.internal.shaded.org.jctools.util.Pow2;

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

    // MSB is used to store the CLOSED flag
    private static final long CLOSED_BIT = 1L << 63;
    private static final long CLOSED_MASK = ~CLOSED_BIT;
    // LSB is used during resize (bit 0)
    private static final long RESIZE_BIT = 1L;

    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private long producerIndex;
    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private long consumerIndex;
    @SuppressWarnings("FieldMayBeFinal") // Modified via VarHandle
    private long producerLimit;

    private long producerMask;
    private E[] producerBuffer;
    private long consumerMask;
    private E[] consumerBuffer;

    public MpscUnboundedStream(int initialCapacity) {
        if (initialCapacity < 2) {
            throw new IllegalArgumentException("Initial capacity must be 2 or more");
        }
        int p2capacity = Pow2.roundToPowerOfTwo(initialCapacity);
        // leave lower bit of mask clear
        long mask = (p2capacity - 1) << 1;
        // need extra element to point at next array
        @SuppressWarnings("unchecked")
        E[] buffer = (E[]) new Object[p2capacity + 1];
        producerBuffer = buffer;
        consumerBuffer = buffer;
        producerMask = mask;
        consumerMask = mask;
        soProducerLimit(mask); // we know it's all empty to start with
    }

    private void soProducerLimit(long v) {
        PRODUCER_LIMIT.setRelease(this, v);
    }

    private long lvProducerLimit() {
        return (long) PRODUCER_LIMIT.getAcquire(this);
    }

    private long lvProducerIndex() {
        return (long) PRODUCER_INDEX.getAcquire(this);
    }

    private boolean casProducerIndex(long expect, long newValue) {
        return PRODUCER_INDEX.compareAndSet(this, expect, newValue);
    }

    private long lvConsumerIndex() {
        return (long) CONSUMER_INDEX.getAcquire(this);
    }

    private void soConsumerIndex(long v) {
        CONSUMER_INDEX.setRelease(this, v);
    }

    private void soProducerIndex(long v) {
        PRODUCER_INDEX.setRelease(this, v);
    }

    private boolean casProducerLimit(long expect, long newValue) {
        return PRODUCER_LIMIT.compareAndSet(this, expect, newValue);
    }

    private static <E> void soElement(E[] buffer, int offset, E e) {
        ARRAY.setRelease(buffer, offset, e);
    }

    @SuppressWarnings("unchecked")
    private static <E> E lvElement(E[] buffer, int offset) {
        return (E) ARRAY.getAcquire(buffer, offset);
    }

    /**
     * Offers an element to the queue. This method can be called by multiple producers.
     *
     * @param e the element to add
     * @return true if the element was added, false if the queue is closed
     */
    public boolean offer(E e) {
        if (null == e) {
            throw new NullPointerException();
        }

        long mask;
        E[] buffer;
        long pIndex;

        while (true) {
            long producerLimit = lvProducerLimit();
            pIndex = lvProducerIndex();
            // lower bit is indicative of resize, if we see it we spin until it's cleared
            if ((pIndex & RESIZE_BIT) == 1) {
                continue;
            }
            // higher bit is indicative of closed
            if ((pIndex & CLOSED_BIT) != 0) {
                return false;
            }
            // pIndex is even (lower bit is 0) -> actual index is (pIndex >> 1)

            // mask/buffer may get changed by resizing -> only use for array access after successful CAS.
            mask = this.producerMask;
            buffer = this.producerBuffer;
            // a successful CAS ties the ordering, lv(pIndex)-[mask/buffer]->cas(pIndex)

            // assumption behind this optimization is that queue is almost always empty or near empty
            if (producerLimit <= pIndex) {
                int result = offerSlowPath(mask, pIndex, producerLimit);
                switch (result) {
                    case 0:
                        break;
                    case 1:
                        continue;
                    case 2:
                        return false;
                    case 3:
                        resize(mask, buffer, pIndex, e);
                        return true;
                }
            }

            if (casProducerIndex(pIndex, pIndex + 2)) {
                break;
            }
        }
        // INDEX visible before ELEMENT, consistent with consumer expectation
        final int offset = modifiedCalcElementOffset(pIndex, mask);
        soElement(buffer, offset, e);
        return true;
    }

    /**
     * We do not inline resize into this method because we do not resize on fill.
     */
    private int offerSlowPath(long mask, long pIndex, long producerLimit) {
        int result;
        final long cIndex = lvConsumerIndex();
        long bufferCapacity = getCurrentBufferCapacity(mask);
        result = 0;// 0 - goto pIndex CAS
        if (cIndex + bufferCapacity > pIndex) {
            if (!casProducerLimit(producerLimit, cIndex + bufferCapacity)) {
                result = 1;// retry from top
            }
        }
        // full and cannot grow
        else if (availableInQueue(pIndex, cIndex) <= 0) {
            result = 2;// -> return false;
        }
        // grab index for resize -> set lower bit
        else if (casProducerIndex(pIndex, pIndex + 1)) {
            result = 3;// -> resize
        }
        else {
            result = 1;// failed resize attempt, retry from top
        }
        return result;
    }

    private void resize(long oldMask, E[] oldBuffer, long pIndex, final E e) {
        int newBufferLength = getNextBufferSize(oldBuffer);
        @SuppressWarnings("unchecked")
        final E[] newBuffer = (E[]) new Object[newBufferLength];

        producerBuffer = newBuffer;
        final int newMask = (newBufferLength - 2) << 1;
        producerMask = newMask;

        final int offsetInOld = modifiedCalcElementOffset(pIndex, oldMask);
        final int offsetInNew = modifiedCalcElementOffset(pIndex, newMask);


        soElement(newBuffer, offsetInNew, e);// element in new array
        soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);// buffer linked

        // ASSERT code
        final long cIndex = lvConsumerIndex();
        final long availableInQueue = availableInQueue(pIndex, cIndex);
        if (availableInQueue <= 0) {
            throw new IllegalStateException();
        }

        // Invalidate racing CASs
        // We never set the limit beyond the bounds of a buffer
        soProducerLimit(pIndex + Math.min(newMask, availableInQueue));

        // make resize visible to the other producers
        soProducerIndex(pIndex + 2);

        // INDEX visible before ELEMENT, consistent with consumer expectation

        // make resize visible to consumer
        soElement(oldBuffer, offsetInOld, JUMP);
    }

    private int nextArrayOffset(final long mask) {
        return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
    }

    protected int getNextBufferSize(E[] buffer) {
        return buffer.length;
    }

    protected long getCurrentBufferCapacity(long mask) {
        return mask;
    }

    protected long availableInQueue(long pIndex, long cIndex) {
        return Integer.MAX_VALUE;
    }

    /**
     * This method assumes index is actually (index << 1) because lower bit is used for resize. This is
     * compensated for by reducing the element shift. The computation is constant folded, so there's no cost.
     */
    private static int modifiedCalcElementOffset(long index, long mask) {
        return (int) ((index & mask) >> 1);
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        final E[] buffer = consumerBuffer;
        final long index = consumerIndex;
        final long mask = consumerMask;

        final int offset = modifiedCalcElementOffset(index, mask);
        Object e = lvElement(buffer, offset);// LoadLoad
        if (e == null) {
            if (index != (lvProducerIndex() & CLOSED_MASK)) {
                // poll() == null iff queue is empty, null element is not strong enough indicator, so we must
                // check the producer index. If the queue is indeed not empty we spin until element is
                // visible.
                do {
                    e = lvElement(buffer, offset);
                } while (e == null);
            }
            else {
                return null;
            }
        }
        if (e == JUMP) {
            final E[] nextBuffer = getNextBuffer(buffer, mask);
            return newBufferPoll(nextBuffer, index);
        }
        soElement(buffer, offset, null);
        soConsumerIndex(index + 2);
        return (E) e;
    }

    private E[] getNextBuffer(final E[] buffer, final long mask) {
        final int nextArrayOffset = nextArrayOffset(mask);
        @SuppressWarnings("unchecked")
        final E[] nextBuffer = (E[]) lvElement(buffer, nextArrayOffset);
        soElement(buffer, nextArrayOffset, null);
        return nextBuffer;
    }

    private E newBufferPoll(E[] nextBuffer, final long index) {
        final int offsetInNew = newBufferAndOffset(nextBuffer, index);
        final E n = lvElement(nextBuffer, offsetInNew);// LoadLoad
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        soElement(nextBuffer, offsetInNew, null);// StoreStore
        soConsumerIndex(index + 2);
        return n;
    }

    private int newBufferAndOffset(E[] nextBuffer, final long index) {
        consumerBuffer = nextBuffer;
        consumerMask = (nextBuffer.length - 2) << 1;
        final int offsetInNew = modifiedCalcElementOffset(index, consumerMask);
        return offsetInNew;
    }

    public final boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPSC q there is
        // nothing we can do to make this an exact method.
        return (this.lvConsumerIndex() == (this.lvProducerIndex() & CLOSED_MASK));
    }

    public final int size() {
        // NOTE: because indices are on even numbers we cannot use the size util.

        /*
         * It is possible for a thread to be interrupted or reschedule between the read of the producer and
         * consumer indices, therefore protection is required to ensure size is within valid range. In the
         * event of concurrent polls/offers to this method the size is OVER estimated as we read consumer
         * index BEFORE the producer index.
         */
        long after = lvConsumerIndex();
        long size;
        while (true) {
            final long before = after;
            final long currentProducerIndex = lvProducerIndex();
            after = lvConsumerIndex();
            if (before == after) {
                // Mask out CLOSED bit for size calculation
                size = (((currentProducerIndex & CLOSED_MASK) - after) >> 1);
                break;
            }
        }
        // Long overflow is impossible, so size is always positive. Integer overflow is possible for the unbounded
        // indexed queues.
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        else {
            return (int) size;
        }
    }

    /**
     * Marks the queue as closed. After this, no more elements can be offered.
     * This is done by setting the MSB of the producer index.
     */
    @Override
    public void close() {
        long pIndex;
        while (true) {
            pIndex = lvProducerIndex();
            if ((pIndex & CLOSED_BIT) != 0) {
                return;
            }
            if (casProducerIndex(pIndex, pIndex | CLOSED_BIT)) {
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
        return (lvProducerIndex() & CLOSED_BIT) != 0;
    }
}
