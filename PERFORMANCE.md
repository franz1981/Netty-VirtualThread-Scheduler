# Performance tuning: why latency degrades and how to fix it

This document explains why the Netty VT scheduler shows elevated latency at moderate
load, what causes it, and which knobs fix it — with measured data for each claim.

## Architecture in one paragraph

Each **carrier** is an OS platform thread that runs a poll-drain loop: discover I/O
events via epoll (`runIO`), then drain queued virtual threads (`drainContinuations`,
50µs budget). Virtual threads (VTs) have **carrier affinity** — a VT's I/O is
registered on its home carrier's event loop, and by default only that carrier drains
it. The **ForkJoinPool (FJP)** baseline runs Netty event loops on dedicated platform
threads and VTs on separate FJP worker threads — no affinity, any worker can process
any task.

## Test environment

- **CPU:** AMD Ryzen 9 7950X (32 logical cores, 2 NUMA nodes)
- **Kernel:** Linux 7.0.9 (Fedora 43, EEVDF scheduler — not CFS)
- **JDK:** Custom Loom build (Java 27), `-Xms4g -Xmx4g`, G1GC (default).
  GC pauses were not isolated from the measurements. At 50K req/s with 4g heap,
  G1 young-gen pauses are typically <1ms and infrequent. Results are representative
  of production-like conditions where GC is present.
- **Netty:** 4.2.13 with wakeup event count fix (issue #112)
- **Transport:** epoll for all results unless noted otherwise
- **Warmup:** 10s at the target rate before measurement begins, 30s steady state.
  wrk2 runs for 40s total (10s warmup + 30s measured). JIT compilation stabilizes
  during warmup.
- **Benchmark:** `benchmark-runner/scripts/run-benchmark.sh` with wrk2 (coordinated-omission corrected)
- **CPU-bound workload:** 2 carriers on CPUs 2,3 via `taskset`; 100 keep-alive
  connections; wrk2 on CPUs 0,1,4,5
- **Mock server:** a separate Netty HTTP server on CPUs 6,7 that responds after a
  configurable think time (1ms for CPU-bound, 30ms for I/O-bound). Each request
  makes a blocking HTTP call to the mock, simulating a backend service call.

**CPUs** in all tables = `task-clock / elapsed_time` from `perf stat`, representing
average number of cores consumed by the server process during the measurement window.

## The problem

At 50K req/s (70% of max 72K TPS), our scheduler's p50 latency is 3.80ms.
FJP achieves 1.20ms on the same hardware. Both process the same requests with the
same per-event cost (~9µs). Where does the 2.6ms gap come from?

## Measurement: per-request phase instrumentation

We added `System.nanoTime()` counters at 6 points per request: READ (channelRead),
VT_START, VT_PARK (before mock call), VT_RESUME (after mock), WRITE_SUBMIT
(`eventLoop.execute`), WRITE_EXEC (Netty processes write). This is lightweight —
unlike JFR `RequestPhaseEvent` (250K events/s), which added enough overhead to equalize
both configs at ~5ms p50, masking the actual difference.

**Result:** the per-request pipeline (READ→WRITE_EXEC) takes ≤2ms p50 in ALL configs.
The gap is not in request processing.

## Root cause (inferred by elimination): carrier preemption

Pinning carriers to dedicated cores — with no code changes — eliminates the latency
gap. Since pinning is the only variable changed, the gap is caused by whatever pinning
removes: OS scheduler interference with carrier threads.

### The evidence

We used JFR events (`NettyRunIo`, `VirtualThreadTaskRuns`, `VirtualThreadTaskSubmit`)
to trace carrier behavior. Pinning each carrier to its own core via `sched_setaffinity`
(no code changes) dropped p50 from 3.80ms to 1.16ms. The JFR data shows why:

| Metric | 2 cores, no pin | 2 cores, pinned |
|---|---|---|
| IO cycles/10s | 247K | 632K (2.6x) |
| IO events/cycle | 4.0 | 1.6 |
| VT drains/10s | 210K | 406K (1.9x) |
| VTs/drain | 5.5 | 3.6 |
| Queue depth p50 | 8 | 1 |
| Master-Poller → JDK Poller submissions | 165K | 474K (2.9x) |
| Per-event IO cost | ~9µs | ~9µs (same) |

**The per-event cost is identical.** Pinning doesn't make operations faster. The
carriers cycle 2.6x more often because they react to wakeup signals faster — each
poll discovers 1.6 events instead of 4.0 (trickle mode vs batch mode).

### Master-Poller notifications confirm the pattern

The JDK **Master-Poller** is a platform thread that monitors sockets for I/O
completions (via its own epoll) and unparks VTs when data arrives. It submits VTs
to our scheduler. We measured its submit→run delay (time from submission to the VT
actually executing on a carrier):

| Master-Poller → JDK Poller | No pin | Pinned |
|---|---|---|
| Submissions/10s | 37K | 182K (5x more) |
| submit→run p50 | 7µs | 6µs (same) |
| submit→run p99 | 1,538µs | 48µs (32x tighter) |
| submit→run avg | 85µs | 10µs |

5x more notifications with pinning, each handled at the same p50 (~7µs), but the
tail collapses: p99 from 1.5ms to 48µs. Without pinning, occasional preemptions
delay individual notifications by over 1ms.

### Why preemption happens and why pinning fixes it

With `taskset` confining the process to CPUs 2,3, the 2 carriers share those cores
with GC threads, JIT compiler (C2), and the JDK Master-Poller. `pidstat` shows:

| | 2 cores, no pin | 2 cores, pinned | 3 cores, no pin |
|---|---|---|---|
| %wait per carrier | 23% | 2% | 7.6% |
| vol cswch/s | 1,244 | 7,273 | 9,737 |
| nvol cswch/s | 1,072 | 4,067 | 1,139 |

**23% of carrier time is runnable-but-waiting.** But why does pinning fix this when
housekeeping threads still share the same 2 cores?

**Directly observed via `perf sched record` (10s, `--perf-sched` flag):**

Without pinning, carriers migrate freely between CPUs 2 and 3:

```
Thread-0: CPU 2 = 6616 (44%), CPU 3 = 8368 (56%)
Thread-1: CPU 2 = 8011 (55%), CPU 3 = 6617 (45%)
```

Both carriers run nearly equally on both cores — EEVDF does not keep them put.
The Master-Poller has more scheduling events than either carrier, competing for
CPU time on both cores:

```
UNPINNED (perf sched record, 10s):
                    CPU 2         CPU 3         scheduling events
Thread-0            6,616 (44%)   8,368 (56%)   14,984
Thread-1            8,011 (55%)   6,617 (45%)   14,628
Master-Poller      12,608 (53%)  11,386 (47%)   23,994
```

Carrier scheduling latency (runqueue wait — time from wakeup to actually getting
CPU): avg=112-115µs, max=6.0ms.

With pinning, each carrier stays on its core. All threads are more active —
carriers have 3.5x more scheduling events because they cycle faster without
migration overhead:

```
PINNED (perf sched record, 10s):
                    CPU 2         CPU 3         scheduling events
Thread-0           55,063 (100%)  0             55,063
Thread-1            0             50,991 (100%) 50,991
Master-Poller      39,657 (52%)  36,877 (48%)  76,534
```

**Zero carrier migrations.** The Master-Poller still runs on both cores (52/48%)
— pinning carriers doesn't exclude other threads from those cores.

To collect this data: `--perf-sched` flag. Outputs `perf-sched-latency.txt`
(per-thread scheduling delay), `perf-sched-distribution.txt` (per-thread CPU
distribution), and `perf-sched-our-migrations.txt` (migration events for process
threads). A `jcmd Thread.print` dump is saved to `server-threads.txt` for
TID↔thread name correlation.

`perf sched latency` on CPUs 2,3 (5s sample, unpinned) confirmed:

```
Thread-0:  avg delay=24µs, max delay=4.644ms, 3235 wakeups
Thread-1:  avg delay=22µs, max delay=4.575ms, 3574 wakeups
```

Most wakeups are fast, but max delays reach 4.6ms — one EEVDF scheduling slice.

### Who wakes the carriers

The `--wakeup-trace` flag captures kernel stacks at every `sched_waking` event,
classifying each wakeup by mechanism (from `wakeup-trace-summary.txt`):

**Baseline (no spin, 2 cores, 50K req/s):**

| Target | total | eventfd | network | ratio |
|---|---|---|---|---|
| Master-Poller | 21,692 | 0 | 21,252 | 98% network |
| Thread-0 (carrier) | 6,320 | 3,812 | 2,494 | 60% eventfd, 39% network |
| Thread-1 (carrier) | 5,859 | 3,643 | 2,204 | 62% eventfd, 38% network |

Top waker→target pairs:

| Waker | Target | Count | Mechanism |
|---|---|---|---|
| Mock server (epoll thread) | Master-Poller | 21,252 | network: downstream responses |
| Master-Poller | Carriers | 7,451 | eventfd: VT submissions |
| Load generator (epoll thread) | Carriers | 4,698 | network: upstream requests |

Three-party wakeup chain: Mock → Master-Poller → Carriers (eventfd), and
LoadGen → Carriers (network, bypassing Master-Poller).

**With spinning (256 spins):** carriers almost never block — 597 wakeups vs 12,179
baseline (20x fewer). When they do block, 75% are eventfd (VT submissions).
The Master-Poller still receives 80K network wakeups because it always blocks
between polls.

### Corroboration: N+1 cores removes most of the penalty

Adding one extra core (CPUs 2,3,4) gives housekeeping threads room to run without
preempting carriers:

| Config | p50 | p99 | CPUs |
|---|---|---|---|
| 2 cores (CPUs 2,3), no pin | 3.80ms | 32ms | 1.46 |
| 2 cores, pinned | 1.16ms | 11ms | 1.75 |
| 3 cores (CPUs 2,3,4), no pin | 1.13ms | 23ms | 1.88 |

The p50 matches pinning (1.13 vs 1.16ms). The p99 is still higher (23ms vs 11ms) —
preemption is reduced but not fully eliminated, since housekeeping threads can still
occasionally land on the carriers' cores. No code changes needed.

### The platoon effect

Batch formation is self-reinforcing — analogous to **platoon formation** in
transportation queueing, where synchronized arrivals perpetuate synchronized
departures. When the carrier discovers N events in one poll:

1. N channelReads → N VTs → N mock calls depart within ~50µs
2. N responses arrive ~1ms later in a ~50µs window
3. Next poll discovers ~N completions → cycle repeats

Each carrier alternates between I/O polling ("vacation") and VT draining ("service").
At constant service capacity, latency is driven by vacation duration — how long between
consecutive drain cycles. Preemption extends vacations, more events accumulate, larger
batches form.

## What helps latency

### Quick reference

| Config | p50 | p99 | CPUs | Max TPS | Mechanism |
|---|---|---|---|---|---|
| Baseline (2 cores, no WS) | 3.80ms | 32ms | 1.46 | 72K | — |
| WS steal-only (no wake) | 3.78ms | 38ms | 1.51 | n/m | Steal without signaling (ineffective) |
| WS ON (steal=2, wake=8) | 2.59ms | 27ms | 1.53 | 73K | Sibling steals overflow |
| WS ON (unresponsive=0) | 1.60ms | 20ms | 1.61 | 72K | Always-steal |
| Spin 256 | 1.13ms | 20ms | 1.90 | 72K | Prevents blocking |
| Spin 256 + WS | 1.12ms | 15ms | 1.91 | 73K | Both |
| Pinned (no spin, no WS) | 1.16ms | 11ms | 1.75 | 72K | Eliminates preemption |
| 3 cores (no pin, no WS) | 1.13ms | 23ms | 1.88 | n/m | Housekeeping gets own core |
| **FJP** | **1.20ms** | **29ms** | **1.82** | **61K** | Separate I/O + VT threads |

Max TPS values marked "n/m" were not measured for these experimental configs.
All Max TPS numbers are averages of 3 runs.

### 1. Allocate N+1 cores (simplest)

Give the process one more core than the number of carriers. No code changes needed.

### 2. Per-carrier core pinning

Pin each carrier via `sched_setaffinity`. Same effect as N+1 at 2 carriers. The higher
CPU usage (+0.28 CPUs) comes from eliminated `%wait` — carriers convert waiting time
into productive work. No effect on I/O-bound workloads where carriers are under-utilized.

### 3. Idle spinning (`io.netty.loom.idleSpins`)

Spins N iterations with `Thread.onSpinWait()` before blocking in `epoll_wait`. Keeps
the carrier in the non-blocking poll path.

**Transport-dependent poll cost** — measured under continuous spinning:

| Transport | Poll cost | 256 spins |
|---|---|---|
| epoll | ~0.42µs (syscall) | ~108µs |
| io_uring | ~0.05µs (shared-memory CQ peek) | ~13µs |

The same spin count is not interchangeable across transports (8.4x cost difference).

### 4. Work stealing with split thresholds

Two thresholds control different decisions:

- **`steal.queue` (default 2):** queue depth for an already-awake carrier to consider
  stealing. Cheap — queue size check, no syscall.
- **`wake.queue` (default 8):** queue depth for `execute()` to wake a sleeping sibling
  via eventfd. Expensive — syscall.

**Why `wakeIdleSibling` is essential:** disabling it while keeping steal enabled
(`-Dio.netty.loom.workstealing.wake.enabled=false`) gave p50=3.78ms — identical to
baseline. Without waking, the sibling stays parked and never reaches `tryStealing`.
The wake signal is what gets the sibling to start probing.

**Unresponsive threshold sweep:** the `unresponsive.ms` threshold controls when a
carrier is considered stale enough for the wakeup path to fire. Max TPS was measured
only at the extremes; intermediate values showed no TPS difference in spot checks.

| Threshold | p50 |
|---|---|
| 200ms (default) | 2.61ms |
| 4ms | 2.95ms |
| 1ms | 2.61ms |
| 100µs | 3.18ms |
| 10µs | 3.11ms |
| 1µs | 2.31ms |
| 500ns | 2.29ms |
| 0 | 1.60ms |

Max TPS at 0: 72.2K (avg of 3 runs) — no regression vs baseline 71.9K.

Sharp cliff at 0 — with threshold=0, `isUnresponsive` is always true (any nanoTime
delta > 0), turning every submission with queued work into a sibling wake signal.

### When to use what

| Scenario | Recommendation |
|---|---|
| Latency-sensitive, can spare a core | N+1 cores |
| Latency-sensitive, fixed core count | Spin 256 + WS (steal=2, wake=8) |
| Throughput-first, some latency help | WS ON with defaults |
| I/O-bound (30ms+ think time) | No tuning needed — carriers under-utilized |

## FJP comparison

FJP runs 5 OS threads on the same 2 CPUs: 2 Netty event loop platform threads + 2
FJP worker threads + 1 Master-Poller. Our scheduler uses 3 (2 carriers + Master-Poller).

**Why FJP achieves 1.20ms without pinning or spinning:**
- **Preemption resilience:** FJP's shared work deque means preempting one worker
  doesn't stall requests — the other worker steals them. Preemption delays the THREAD
  but not the WORK. In our scheduler (WS OFF), preempting a carrier stalls its entire
  MPSC queue.
- **More threads than cores:** 5 threads on 2 cores means the OS always has runnable
  threads to schedule. FJP workers themselves show 31% `%wait` (worse than our 23%),
  but this doesn't matter because work flows to whichever worker gets CPU next. Our
  carriers' 23% `%wait` directly translates to request stalling because work is pinned
  to specific carriers.

**FJP's internal spinning doesn't matter:** FJP workers scan 128 iterations
(`SPIN_WAITS = 1 << 7`, array reads checking deques for work) before parking. We patched the JDK to make this
configurable (`-Djdk.forkJoinPool.spinWaits=0`) and measured:

| FJP spin | p50 | CPUs |
|---|---|---|
| 128 (default) | 1.20ms | 1.83 |
| 0 (disabled) | 1.19ms | 1.82 |

No effect. FJP's latency comes from architecture (shared queues + thread slack),
not from spinning.

**FJP's trade-off:** 15% lower max TPS (61K vs 72K) from cross-thread coordination —
5 threads competing for 2 CPUs produce 9K non-voluntary context switches/s and 31%
`%wait`.

## I/O-bound results (8 carriers, 30ms mock, 10K connections)

| Config | Max TPS (avg of 3 runs) |
|---|---|
| Baseline | 165K |
| WS aggressive (steal=2, wake=8) | 163K |
| WS aggressive (unresponsive=0) | 161K |
| FJP | 149K |

Our scheduler beats FJP by 10% on I/O-bound throughput. WS aggressive shows ≤3%
regression — within noise. Pinning and spinning have no effect at I/O-bound load
(carriers use 3-4 of 8 CPUs, no preemption contention).

## Reproducing

All data in this document is collected via `benchmark-runner/scripts/run-benchmark.sh`.
Flags: `--perf-stat` (CPU utilization), `--perf-sched` (scheduling events, migrations,
latency), `--wakeup-trace` (who wakes whom via bpftrace + kernel stacks), `--jfr`
(Netty event timing). pidstat is enabled by default.

```bash
export JAVA_HOME=/path/to/loom/build/linux-x86_64-server-release/jdk

# Build
mvn -pl benchmark-runner -am package -DskipTests

# Baseline at 50K with full tracing
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --server-cpuset 2,3 --mock-cpuset 6,7 --load-cpuset 0,1,4,5 \
  --mock-think-time 1 --perf-stat --perf-sched --wakeup-trace

# Work stealing aggressive
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --server-cpuset 2,3 --mock-cpuset 6,7 --load-cpuset 0,1,4,5 \
  --jvm-args "-Dio.netty.loom.workstealing.enabled=true \
    -Dio.netty.loom.workstealing.steal.queue=2 \
    -Dio.netty.loom.workstealing.wake.queue=8" --perf-stat

# Spin 256
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --server-cpuset 2,3 --mock-cpuset 6,7 --load-cpuset 0,1,4,5 \
  --idle-spins 256 --perf-stat --perf-sched --wakeup-trace

# N+1 cores
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --server-cpuset 2,3,4 --mock-cpuset 6,7 --load-cpuset 0,1,5 \
  --mock-think-time 1 --perf-stat

# Per-carrier pinning (manual — not yet a benchmark script feature)
# The server prints CARRIER id=<java-tid> name=<name> at startup.
# Correlate Java thread ID with native TID via jcmd Thread.print
# (shows "#<java-id> [<native-tid>]"), then pin with taskset.
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --server-cpuset 2,3 --mock-cpuset 6,7 --load-cpuset 0,1,4,5 \
  --mock-think-time 1 --perf-stat &
sleep 12
SERVER_PID=$(lsof -i :8081 -t | head -1)
# Map Java thread IDs to native TIDs and pin round-robin
CPU=2
for java_id in $(grep -oP 'CARRIER id=\K\d+' benchmark-results/server-output.log); do
  native_tid=$(jcmd "$SERVER_PID" Thread.print | \
    grep -P "^\".*#${java_id}\b" | grep -oP '\[\K\d+(?=\])')
  [ -n "$native_tid" ] && taskset -cp $CPU $native_tid
  CPU=$((CPU + 1))
done
wait

# FJP baseline
benchmark-runner/scripts/run-benchmark.sh --rate 50000 \
  --mode NON_VIRTUAL_NETTY --server-cpuset 2,3 --mock-cpuset 6,7 \
  --load-cpuset 0,1,4,5 --mock-think-time 1 --perf-stat

# Quick comparison (all configs, 3 runs each)
./run-quick-comparison.sh 3
```

### Output files

Each run produces in the output directory:

| File | Flag | Content |
|---|---|---|
| `wrk-results.txt` | (always) | Latency percentiles, throughput |
| `pidstat.log` | (default on) | Per-thread CPU, context switches, %wait |
| `perf-stat.txt` | `--perf-stat` | Hardware counters, CPUs utilized |
| `perf-sched-latency.txt` | `--perf-sched` | Per-thread scheduling delay (avg/max) |
| `perf-sched-distribution.txt` | `--perf-sched` | Per-thread CPU distribution |
| `perf-sched-our-migrations.txt` | `--perf-sched` | Carrier migration events |
| `wakeup-trace-summary.txt` | `--wakeup-trace` | Who wakes whom (eventfd vs network) |
| `wakeup-trace.txt` | `--wakeup-trace` | Raw bpftrace aggregation with kernel stacks |
| `server-threads.txt` | `--perf-sched` or `--wakeup-trace` | jcmd Thread.print for all components |
