# Performance tuning and latency analysis

This guide covers the latency/throughput trade-offs of the Netty VT scheduler's
tuning knobs: idle spinning, work stealing, and their interaction. All data was
collected on the same machine with the benchmark script.

## Test environment

- **CPU:** AMD Ryzen 9 7950X (32 logical cores)
- **Kernel:** Linux 7.0.9 (Fedora 43)
- **JDK:** Custom Loom build (Java 27)
- **Netty:** 4.2.13 with wakeup event count fix (issue #112)
- **Benchmark:** `benchmark-runner/scripts/run-benchmark.sh`
- **Workload:** CPU-bound — 2 carriers on CPUs 2,3; 1ms mock think time;
  100 connections; fixed rate via wrk2 (coordinated-omission corrected)

```bash
# Example: run at 50K req/s with spin 256 and work stealing
OUTPUT_DIR=/tmp/test LOAD_GEN_RATE=50000 SERVER_MODE=NETTY_SCHEDULER \
  MOCK_THREADS=2 MOCK_CPUSET=6,7 SERVER_CPUSET=2,3 \
  LOAD_GEN_THREADS=4 LOAD_GEN_CPUSET=0,1,4,5 \
  TOTAL_DURATION=40s ENABLE_PERF_STAT=true \
  benchmark-runner/scripts/run-benchmark.sh \
  --idle-spins 256 --jvm-args "-Dio.netty.loom.workstealing.enabled=true"
```

## Summary: what helps and what it costs

At 50K req/s (CPU-bound, 2 carriers, epoll):

| Config | p50 | p99 | CPUs | Max TPS |
|---|---|---|---|---|
| Baseline (WS OFF, spin 0) | 3.80ms | 32ms | 1.46 | 70K |
| WS ON (default thresholds) | 3.41ms | 78ms | 1.53 | 72K |
| WS ON (steal.queue=2, wake.queue=8) | 2.59ms | 27ms | 1.53 | 73K |
| Spin 256, WS OFF | 1.13ms | 20ms | 1.90 | 70K |
| Spin 256, WS ON | 1.12ms | 15ms | 1.91 | 73K |
| **FJP (NON_VIRTUAL_NETTY)** | **1.20ms** | **29ms** | **1.82** | **61K** |

**Key takeaways:**
- Work stealing with low steal threshold gives 32% p50 improvement at +0.07 CPUs
  and actually increases max TPS
- Spin 256 matches FJP latency at +0.44 CPUs — the CPU cost is the price of
  preventing I/O batch formation
- FJP has lower max TPS (61K vs 70K+) because of cross-thread coordination overhead
- WS ON + Spin 256 is the best combination: low latency + tail help from stealing

## Idle spinning (`io.netty.loom.idleSpins`)

### What it does

When the poller has no I/O events and no VT work, it can either block immediately
in `epoll_wait` (spin=0, default) or spin for N iterations before blocking. During
each spin iteration, the poller does a non-blocking I/O poll + `Thread.onSpinWait()`.

### Why it helps

Our scheduler multiplexes I/O polling and VT execution on the same carrier thread.
At moderate load (50K req/s on 2 carriers), each carrier spends 42% of its time on
I/O and 56% on VTs, leaving only 1.7% idle. When the poller blocks in `epoll_wait`,
incoming TCP data accumulates in kernel buffers until the poller wakes. This creates
**batch formation**: N events discovered at once → N VTs → N mock calls → N responses
arrive together → more batching. The cycle is self-reinforcing.

Spinning keeps the poller in the non-blocking path, discovering events individually
instead of in batches. Queue depth drops from p50=8 (no spin) to p50=1 (spin 256).

### Transport-dependent poll cost

Each spin iteration includes a non-blocking I/O poll whose cost depends on the
transport:

| Transport | Poll cost (measured) | 256 spins | 2048 spins |
|---|---|---|---|
| epoll | ~0.42µs (syscall) | ~108µs | ~860µs |
| io_uring | ~0.05µs (shared-memory CQ peek) | ~13µs | ~102µs |

**The same spin count covers vastly different time windows.** An epoll spin of 256
is equivalent to an io_uring spin of ~2048. Tune per transport and measure on your
hardware.

### CPU cost

| Spin count | p50 at 50K | CPUs |
|---|---|---|
| 0 (default) | 3.80ms | 1.46 |
| 64 | 3.28ms | 1.59 |
| 128 | 1.16ms | 1.70 |
| 256 | 1.13ms | 1.90 |
| -1 (always) | 1.13ms | 2.00 |

Spin 128 is a transition point: p50 drops to 1.16ms but p90 remains high (13.89ms)
because some bursts escape the spin window. Spin 256 covers enough wall-clock time
to catch all burst tails on this hardware.

## Work stealing

### How it helps

Work stealing allows idle carriers to take VTs from overloaded siblings. This is
most effective after the wakeup event count fix (Netty issue #112): without the fix,
eventfd wakeups counted as I/O events, causing `maybeYield(true)` which suppressed
the stealing code path.

### Steal vs wake thresholds

Two thresholds control different decisions with different costs:

- **`steal.queue` (default 2):** when an already-awake carrier probes siblings for
  work. Cheap — just a queue size check.
- **`wake.queue` (default 8):** when `execute()` decides to wake a sleeping sibling
  via eventfd. Expensive — syscall.

The defaults are tuned for CPU-bound workloads on 2 carriers. With more carriers
(e.g., 8-core I/O-bound), the wake threshold may need to increase to avoid excessive
wakeup syscalls.

| Config | p50 at 50K | CPUs | Max TPS |
|---|---|---|---|
| WS OFF | 3.80ms | 1.46 | 70K |
| WS ON (steal=10, wake=10) | 3.41ms | 1.53 | 72K |
| WS ON (steal=2, wake=8) | 2.59ms | 1.53 | 73K |

Profiling (async-profiler): WS overhead = 1.1% of CPU samples. The `wakeIdleSibling`
eventfd write is the main cost.

### Interaction with spinning

Spinning and work stealing are complementary:

- **Spin alone** prevents batch formation → low p50 but no help for tail
- **WS alone** distributes burst overflow → lower p50, helps tail via sibling
- **Both** prevents batches AND distributes any remaining overflow → lowest p50
  and best p99

| Config | p50 | p99 |
|---|---|---|
| Neither | 3.80ms | 32ms |
| WS only | 2.59ms | 27ms |
| Spin only | 1.13ms | 20ms |
| Both | 1.12ms | 15ms |

## Latency curves (CPU-bound, epoll)

The curves below show p50 latency vs request rate for each configuration.
All curves use the same test environment.

<!-- TODO: add HdrHistogram plots or ASCII curves -->

```
rate    baseline   WS ON(2,8)   spin-256   WS+spin    FJP
 7000   1.12ms     1.12ms       1.12ms     1.12ms     1.13ms
14000   1.12ms     1.12ms       1.12ms     1.12ms     1.13ms
21000   1.13ms     1.13ms       1.13ms     1.13ms     1.15ms
28000   1.13ms     1.13ms       1.13ms     1.13ms     1.14ms
35000   1.15ms     1.16ms       1.15ms     1.15ms     1.15ms
42000   3.18ms     2.39ms       1.16ms     1.16ms     1.16ms
49000   4.08ms     3.26ms       1.17ms     1.17ms     1.17ms
52500   5.96ms     3.41ms       1.18ms     1.18ms     1.18ms
56000   4.65ms     2.23ms       1.20ms     1.20ms     1.20ms
59500   2.69ms     1.40ms       1.22ms     1.22ms     1.22ms
63000   1.32ms     1.85ms       1.26ms     1.26ms     1.26ms
```

**Below 40% load:** all configs identical — no batching at low utilization.

**40-75% load:** baseline degrades to 3-6ms. WS ON (steal.queue=2) stays at
2-3ms. Spin 256 and FJP stay flat at ~1.2ms.

**Above 85% load:** all configs converge as CPU saturation dominates.

## FJP comparison

FJP (NON_VIRTUAL_NETTY) runs Netty event loops on 2 dedicated platform threads
and VTs on 2 FJP workers — 5 OS threads total on 2 CPUs. Our scheduler uses 3
OS threads (2 carriers + Master-Poller).

FJP's latency advantage comes from three factors:
1. **Separate I/O and VT execution** — neither blocks the other
2. **VT distribution** — FJP's work-stealing distributes VTs from one Netty EL
   across both workers
3. **Lower per-resource utilization** — each thread handles less work, reducing
   queueing delays

FJP's throughput disadvantage (61K vs 70K+) comes from cross-thread coordination:
5 threads competing for 2 CPUs, 9K non-voluntary context switches/s, 31% `%wait`.

## Reproducing

```bash
# All configurations use the benchmark script:
OUTPUT_DIR=/tmp/test SERVER_MODE=NETTY_SCHEDULER \
  MOCK_THREADS=2 MOCK_CPUSET=6,7 SERVER_CPUSET=2,3 \
  LOAD_GEN_THREADS=4 LOAD_GEN_CPUSET=0,1,4,5 \
  TOTAL_DURATION=40s ENABLE_PERF_STAT=true \
  benchmark-runner/scripts/run-benchmark.sh [OPTIONS]

# Baseline (no spin, no WS)
--rate 50000

# Work stealing with aggressive thresholds
--rate 50000 --jvm-args "-Dio.netty.loom.workstealing.enabled=true \
  -Dio.netty.loom.workstealing.steal.queue=2 \
  -Dio.netty.loom.workstealing.wake.queue=8"

# Spin 256
--rate 50000 --idle-spins 256

# FJP baseline
--rate 50000 --mode NON_VIRTUAL_NETTY

# Max throughput (no rate limit)
--mode NETTY_SCHEDULER --idle-spins 256
```
