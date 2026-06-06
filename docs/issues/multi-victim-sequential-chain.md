# Sequential chain for multi-victim steal: Go's soft approach for load imbalance at 8+ carriers

## Problem

⚠️ **Still open — no burst data at 8+ carriers with load imbalance**

With 8+ carriers and load imbalance, the current `wakeFirstIdle` approach lacks burst-handling data. When a single carrier in a cluster of 4 cores slows down, the remaining idle carriers need to help — but our current sequential scan has limitations that may not scale.

The core scenario: in a cluster of 4 cores, 1 slows down (GC pause, OS scheduling, thermal throttling, etc.). You need the other idle cores to absorb the burst. But if you have 2 slowing down, you need the remaining 2 idle ones to help — can you afford soft limits that exceed half your cores?

## Go's Approach: Sequential Victim Chain (Soft Limits)

Go's runtime (`src/runtime/proc.go`) uses a **sequential chain** for multi-victim work-stealing:

### How `findRunnable()` works

```
// Pseudocode from Go runtime
func findRunnable() {
    // 1. Check local run queue
    // 2. Check global run queue (every 61st schedule)
    // 3. Poll network
    // 4. Steal from other P's:
    //    - Pick random start via fastrand()
    //    - Iterate ALL P's sequentially (wrapping around)
    //    - Steal up to half the victim's run queue
}
```

### Key mechanisms

1. **No explicit utilization measurement**: Go does NOT measure per-P utilization. Instead:
   - Global `npidle` counter tracks number of idle P's
   - `needspinning()` heuristic limits spinning M's to `GOMAXPROCS / 2`
   - At most `sched.nmspinning` goroutines actively searching

2. **Throttling searchers, not victims**: Go limits **how many threads can be in search state simultaneously**:
   ```
   func wakep() {
       if sched.nmspinning.Load() != 0 || !sched.nmspinning.CompareAndSwap(0, 1) {
           return  // Someone already searching, don't wake more
       }
       // ... wake an idle M
   }
   ```
   The `nmspinning` limit prevents thundering-herd effects without per-core utilization.

3. **Randomized victim selection**: `fastrand()` picks a starting victim index, then sequential iteration through all P's distributes steal pressure evenly.

4. **Steal half the queue**: Amortizes the cost of stealing — one wake-up can move multiple runnable goroutines.

### Why `GOMAXPROCS / 2`?

Go's reasoning: if more than half of processors are already spinning/searching, adding more searchers increases contention without proportionally increasing throughput. The remaining active processors are either:
- Productively executing goroutines, or
- About to finish and become victims themselves

## What We Can Do Without Utilization Measurement

Our current implementation in `EventLoopSchedulerGroup` uses `ClusterState.tryStartSearcher()` which already implements a searcher count — analogous to Go's `nmspinning`. The question is: **what should the cap be?**

### Option A: Cap at `clusterSize / 2` (Go's approach)

```
// In ClusterState.tryStartSearcher():
if (nSearching >= clusterSize / 2) return false;
```

**Pro**: Prevents thundering herd, matches Go's battle-tested heuristic.
**Con**: With cluster size 4, this means at most 2 searchers. If 2 carriers are overloaded, only 2 can help — but they're also the ones that are supposed to be idle helpers.

### Option B: Cap at `clusterSize - 1` (aggressive)

Always allow all-but-one to search. Maximizes steal bandwidth.

**Con**: High contention on victim queues, wasted cycles on failed steals.

### Option C: Adaptive cap based on idle count

```
int cap = Math.max(1, idleCount / 2);
```

Scale the searcher cap based on how many carriers are actually idle. More idle = more search bandwidth allowed.

### Option D: Sequential victim chain with rotating start

Instead of bitmap scan from word 0 (lowest-ID first), rotate the start position:
```
int start = (lastWakeIndex.getAndIncrement()) % capacity;
// scan from start, wrap around
```

This distributes which carrier gets woken, preventing lowest-ID starvation.

## Relationship to the Half-Cores Question

> You cannot afford to make soft limits exceed half of your cores (?) or not.

The tension is:
- **Too few searchers** (< half): Under bursty load, available help sits idle while one carrier drowns
- **Too many searchers** (> half): Wasted wake-ups, CAS contention, cache pollution

Go's answer: cap at half, but make each steal move MORE work (steal-half semantics). This means fewer wake-ups are needed to redistribute work.

**Our answer should likely be**: cap searchers at `clusterSize / 2`, BUT when we do wake a searcher, direct it precisely to the overloaded victim (which we already do via `SEARCHING + victimId`). This is actually better than Go because our directed steal avoids the random walk.

## References

- Go runtime: `src/runtime/proc.go` — `findRunnable()`, `wakep()`, `stealWork()`
- Go's `nmspinning` invariant: `src/runtime/proc.go:3133`
- Current implementation: `IdleCarrierTracker.wakeFirstIdle()`, `EventLoopScheduler.signalWorkFor()`
- Cluster state: `EventLoopSchedulerGroup.ClusterState.tryStartSearcher()`
