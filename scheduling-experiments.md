# Scheduling Experiments Log

All tests: epoll, 2 server cores (2,3), 4 wrk cores (0,1,4,5), 2 mock cores (6,7),
100 connections, 1ms mock think time, same NUMA node, CPU capped 4.3GHz.

## Baseline (interleaved loop, 10us yield)

Poller loop: `runIO → maybeYield → runNonBlockingTasks → maybeYield`
Carrier drain: `runExternalContinuations(10us deadline)`

| Metric | Value |
|--------|-------|
| Mock max TPS | 73K |
| Mockless max TPS | 139K |
| p50 @ 60K | 9.7ms (best of 3) |
| Split topology p50 @ 60K | 2.5ms |

JFR findings:
- 343K runIO calls/10s (34K/s per carrier), avg 2.6 events/poll, 18% zero-event
- 681K VT drain batches, avg 1.9 VTs/batch, avg queue depth 10.5
- 598K unnamed VT submits (same-carrier, no wakeup)
- Each VT continuation runs ~10us

## Experiment 1: VT drain budget 50us

Changed carrier loop: `runExternalContinuations(50us)` instead of 10us.

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 70K | -4% |
| p50 @ 60K | 5.5ms | -43% improvement |

Best latency improvement. Carrier drains ~5 VTs per yield instead of ~1.

## Experiment 2: Batch I/O loop

Poller loop: `runIO; while(ioEvents>0) runNow(); maybeYield; runTasks; maybeYield`

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 73K | same |
| p50 @ 60K | 6.5ms | -33% |

No throughput regression but latency improvement less than 50us drain alone.

## Experiment 3: Batch I/O + no separate runNonBlockingTasks

Poller loop: `runIO; while(ioEvents>0) runNow(); maybeYield; canBlock=true`
(runNow already processes tasks internally)

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 73K | same |
| p50 @ 60K | 7ms | -28% |
| Mockless max | 134K | -4% |

## Experiment 4: Single maybeYield per loop

Poller loop: `runIO; runTasks; maybeYield`

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 71K | -3% |
| p50 @ 60K | 8.6ms | worse |

First yield (after runIO) is important for letting VTs run between I/O and tasks.

## Experiment 5: VT-first ordering

Poller loop: `maybeYield; runTasks; runIO; maybeYield`

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 74K | same |
| p50 @ 60K | 7.2ms | -26% |

p50 slightly better, tail worse.

## Experiment 6: Drain all VTs (Long.MAX_VALUE deadline)

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 68K | -7% |
| p50 @ 60K | 18-35ms | MUCH worse |
| Mockless max | 129K | -7% |

Starves the poller. VTs keep arriving during drain.

## Experiment 7: Non-blocking only (no blocking I/O)

| Metric | Value | Delta |
|--------|-------|-------|
| p50 @ 60K | 29ms | catastrophic |

Carrier busy-spins when idle instead of parking efficiently.

## Experiment 8: Snapshot drain (runQueue.size() + drain(limit))

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 72K | -1% |
| p50 @ 60K | 7.2ms | -26% |
| Mockless max | 134K | -4% |

runQueue.size() is O(n) on MPSC — adds overhead.

## Experiment 9: drain(Consumer) — drain until null

| Metric | Value | Delta |
|--------|-------|-------|
| Mock max TPS | 69K | -5% |
| p50 @ 60K | 17-135ms | bad, variable |
| Mockless max | 129K | -7% |

Same problem as drain-all: MPSC gets refilled during drain, not a true snapshot.

## Next: Local queue approach (Micronaut-inspired)

globalToLocal: move MPSC → local ArrayDeque, drain local only.
- True snapshot: new arrivals go to MPSC, wait for next cycle
- Same-carrier VT submits could go directly to local queue (cheaper, no CAS)
- JDK poller submits (same carrier) also skip MPSC
- Only cross-carrier submits use MPSC

## pollerMode observations

- pollerMode=3: per-carrier JDK pollers add overhead, p50 9ms
- pollerMode=1: shared pollers, p50 4ms at 60K
- pollerMode=3 adds ~5ms to p50 due to extra VT scheduling on same carrier

## Micronaut ideas to explore

1. Adaptive latency/throughput mode (queue depth > 10 → longer time slices)
2. Local queue for same-carrier submissions (skip MPSC CAS)
3. FIFO/FILO adaptive ordering
4. Warmup tasks on default FJP scheduler

## Experiment 10: Adaptive drain (queue depth > 4 → 50us, else 10us)

| Metric | Value | Delta vs baseline |
|--------|-------|-------------------|
| Mock max TPS | 72K | -1% |
| Mockless max | 137K | -1% |
| p50 @ 60K | 6.2ms | -36% |

Best tradeoff: mockless barely drops, latency close to fixed 50us.

## Experiment 11: Adaptive + JDK poller priority (skip deadline for JDK_POLLER)

| Metric | Value | Delta vs baseline |
|--------|-------|-------------------|
| Mock max TPS | 73.5K | same |
| Mockless max | 138K | same |
| p50 @ 60K | 5.9ms | -39% |
| p90 @ 60K | 18.9ms | -48% |

Best combo. JDK pollers run without time budget → unblock VTs faster.

## Next: Separate JDK poller queue
Submission routes JDK_POLLER tasks to a dedicated queue, drained first before regular VTs.
Poller runs once → unblocks VTs → new tasks spawned → regular drain handles them.
