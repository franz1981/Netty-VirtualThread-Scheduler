# `wakeFirstIdle` lowest-ID bias: ABA problem and bitmap vs. stack trade-off

## Problem

The current `IdleCarrierTracker.wakeFirstIdle()` uses a bitmap scan starting from word 0, bit 0. This creates a **lowest-ID bias**: carrier 0 is always woken first, then carrier 1, etc. This bias creates an ABA problem pattern and unfair distribution of work-stealing duties.

**Constraint**: We want ONE data structure (bitmap OR stack), not both. The question is which one, and how to avoid the ABA problem inherent in each.

## The ABA Problem in Detail

### With Bitmap (current implementation)

```java
// IdleCarrierTracker.wakeFirstIdle() — always starts from word 0
for (int w = 0; w < words.length; w++) {
    long word = (long) WORDS.getVolatile(words, w);
    while (word != 0) {
        int bit = Long.numberOfTrailingZeros(word);  // always picks lowest bit!
        int carrierId = (w << 6) + bit;
        if (group.scheduler(carrierId).wakeupAsSearcher(victim)) {
            return true;
        }
        word &= ~(1L << bit);
    }
}
```

**The ABA scenario:**

1. **Time T0**: Carriers 0, 1, 2, 3 are idle (bitmap = `0b1111`)
2. **Time T1**: Signal arrives → wakes carrier 0 (lowest ID)
3. **Time T2**: Carrier 0 steals work, runs it, finishes, parks again → bitmap = `0b1111`
4. **Time T3**: Another signal → wakes carrier 0 AGAIN
5. **Repeat**: Carrier 0 is constantly hot-pathed, carriers 1-3 remain cold

This is the "ABA" because the bitmap returns to the same state (`A → B → A`), and the scan algorithm cannot distinguish "carrier 0 was just active and re-parked" from "carrier 0 has been idle for a long time."

**Consequences:**
- Carrier 0's cache is constantly churned (steal from varying victims)
- Carriers 1-3 never build cache affinity for their assigned work
- Under moderate load, one carrier does all the stealing while others sleep
- Unfair power distribution — carrier 0's core runs hotter

### With Stack (LIFO idle list)

A stack (Treiber stack or similar) gives **most-recently-parked** semantics:

```
// Push on park
push(carrierId)  // most recent parked is on top

// Pop on wake
carrier = pop()  // wakes the LAST one that parked
```

**The ABA scenario with stack:**

1. **Time T0**: Stack = [3, 2, 1, 0] (carrier 3 parked most recently)
2. **Time T1**: Signal → pop carrier 3, wake it
3. **Time T2**: Carrier 3 finishes, parks → Stack = [3, 2, 1, 0]
4. **Time T3**: Signal → pop carrier 3 AGAIN

Same problem, different polarity! Now it's the **most-recently-parked** carrier that gets all the work.

But the stack ABA is actually WORSE because:
- The "hot" carrier just finished work and re-parked → its cache is warm for ITS OWN work, not for stealing
- Waking it to steal from a different victim pollutes that warm cache
- Stack operations require pointer chasing (slower than bitmap scan on small sets)

### The Core ABA Issue

Both structures suffer from ABA because they lack **temporal ordering** — they cannot distinguish:
- "This carrier has been idle for 1µs" (just re-parked) 
- "This carrier has been idle for 10ms" (genuinely cold, good steal candidate)

## Why Not Both?

Having bitmap AND stack creates consistency problems:
- Must update both atomically on park/wake — ordering bugs
- Bitmap says idle, stack doesn't contain it (or vice versa) 
- Double the memory barriers and CAS operations
- Complexity explosion for minimal gain

## Solutions: Single Data Structure

### Option 1: Bitmap with Rotating Scan Start

Keep the bitmap but eliminate lowest-ID bias:

```java
boolean wakeFirstIdle(EventLoopSchedulerGroup group, EventLoopScheduler victim) {
    int startWord = (int)(scanCounter++ % words.length);  // rotate start
    for (int i = 0; i < words.length; i++) {
        int w = (startWord + i) % words.length;
        long word = (long) WORDS.getVolatile(words, w);
        while (word != 0) {
            int bit = Long.numberOfTrailingZeros(word);
            int carrierId = (w << 6) + bit;
            if (group.scheduler(carrierId).wakeupAsSearcher(victim)) {
                return true;
            }
            word &= ~(1L << bit);
        }
    }
    return false;
}
```

**Pro**: Simple, keeps O(1) per-word scan, distributes wakes across carriers.
**Con**: `scanCounter` is a shared mutable — adds contention. Could use thread-local or victim-ID-derived offset instead.

### Option 2: Bitmap with Victim-ID-Derived Start

Use the victim's ID to deterministically pick which idle carrier to wake:

```java
int startBit = victim.id % capacity;  // start near the victim
int startWord = startBit >>> 6;
```

**Pro**: No shared mutable counter. Naturally distributes — different victims wake different helpers. Good for cache locality (nearby carriers share cache hierarchy).
**Con**: If victims are always the same carrier (the slow one), this degrades back to fixed-start.

### Option 3: FIFO Queue (Ring Buffer)

Replace bitmap with a bounded FIFO ring buffer of idle carrier IDs:

```
// On park: enqueue(carrierId) at tail
// On wake: dequeue from head → oldest idle carrier
```

**Pro**: Wakes the **longest-idle** carrier — maximally cold, no ABA, fair distribution.
**Con**: 
- Requires CAS on head/tail pointers (more expensive than bit-set/clear)
- Cannot do O(1) "is carrier X idle?" check (need separate flag or scan)
- Ring buffer sized to carrier count, but memory is fine

### Option 4: Bitmap + Per-Carrier Epoch (Lightweight Temporal Tag)

Keep bitmap for fast "who is idle?" query, but add a per-carrier generation counter:

```java
// On park: generation[carrierId]++; markIdle(carrierId)
// On wakeFirstIdle: among idle carriers, prefer one with LOWEST generation
//   (has been idle longest / woken least recently)
```

This is still ONE logical structure (bitmap for set membership, parallel array for priority), but gives temporal ordering without a separate stack.

**Pro**: O(N) scan but N ≤ cluster size (typically 4-8), so negligible cost.
**Con**: Adds a VarHandle read per idle carrier during selection.

## Recommendation

For clusters of 4-8 carriers (the common case), **Option 4 (bitmap + epoch)** or **Option 1 (rotating scan)** are the best trade-offs:

- The bitmap remains the authoritative "who is idle?" structure (single source of truth)
- Rotating scan is simplest — just change the start position
- Epoch-based adds fairness but at the cost of one extra read per idle carrier

The key insight: **at cluster sizes of 4-8, the scan cost is negligible** — we're talking about scanning 1 `long` word. The ABA unfairness matters more than scan efficiency.

## The Half-Cores Constraint

> You usually have in a cluster of 4 cores, 1 slowing down. If you have 2 slow you need the other 2 idle to help.

This constrains the solution: if we rotate wakes too aggressively, we might wake a carrier that's about to receive its own new work. The safe heuristic:
- At most `clusterSize / 2` carriers should be in search/steal state simultaneously
- The rotation ensures that among the idle set, each gets fair turns being the helper
- But we never wake MORE than half, preventing the case where all cores are stealing and none are serving

## References

- Current implementation: `IdleCarrierTracker.wakeFirstIdle()` (line 92)
- `findIdle()` has the same lowest-ID bias (line 73)
- `markIdle`/`markActive` are correct (no ABA in the marking itself)
- The ABA manifests only in the **selection** logic (`numberOfTrailingZeros` always picks lowest)
- Go's equivalent: `pidleget()`/`pidleput()` use a linked list (stack-based), but Go's `findRunnable()` randomizes victim selection to compensate
