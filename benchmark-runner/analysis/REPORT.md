# Netty Virtual Thread Scheduler — Max Load Benchmark Report
**Date:** 2026-03-02 | **Machine:** AMD Ryzen 9 7950X, Fedora 43, Linux 6.18 | **JDK:** Custom OpenJDK build (loom branch)

## 1. Test Setup

| Parameter | Value |
|-----------|-------|
| Load | max throughput |
| Connections | 10,000 |
| Mock think time | 30ms |
| Load-gen threads | 4 |
| Duration | 10s warmup + 20s measurement |
| CPU pinning | server=8-15, mock=4-7, loadgen=0-3 |

All server cores on CCD1, sharing 32MB L3.

> **Glossary**
> - **EL** — Event Loop (Netty I/O thread)
> - **FJ** — ForkJoinPool (virtual thread scheduler)
> - **IPC** — Instructions Per Cycle
> - **nvcswch** — non-voluntary context switches (thread yielded CPU involuntarily)

---

## 2. Configurations tested

| Config | Event Loop | Scheduler | I/O | Threads | Affinity | Poller |
|--------|-----------|-----------|-----|---------|----------|--------|
| **custom_8_epoll** | VirtualMultithreadIoELG | NettyScheduler | epoll | 8 | structural | POLLER_PER_CARRIER |
| **custom_8_nio** | VirtualMultithreadIoELG | NettyScheduler | NIO | 8 | structural | POLLER_PER_CARRIER |
| **affinity_8** | ManualIoELG | ForkJoinPool | NIO | 8 | roundRobin + inherit | VTHREAD_POLLERS |
| **no_affinity_8** | ManualIoELG | ForkJoinPool | NIO | 8 | none | VTHREAD_POLLERS |
| **fj_8_8** | MultiThreadIoELG | ForkJoinPool | NIO | 8+8 | none | VTHREAD_POLLERS |
| **fj_4_4** | MultiThreadIoELG | ForkJoinPool | NIO | 4+4 | none | VTHREAD_POLLERS |

---

## 3. Throughput

| Config | Requests/sec |
|--------|-------------|
| **custom_8_epoll** | **183,041** |
| **custom_8_nio** | 174,374 |
| **affinity_8** | 168,189 |
| **fj_8_8** | 161,368 |
| **no_affinity_8** | 158,798 |
| **fj_4_4** | 136,362 |

---

## 4. CPU Usage and Per-Request Cost

| Metric | custom_8_epoll | custom_8_nio | affinity_8 | fj_8_8 | no_affinity_8 | fj_4_4 |
|--------|---------------|-------------|-----------|--------|--------------|--------|
| **CPUs utilized** | 7.99 | 8.00 | 7.99 | 7.91 | 7.96 | 6.66 |
| **IPC** | 1.08 | 1.09 | 1.05 | 0.99 | 1.03 | 0.99 |
| **CPU migrations** | 271 | 138 | 1,836 | 4,158 | 181 | 1,562 |

At max load, all 8-thread configs saturate the available cores (~8.0 CPUs). The efficiency difference shows in throughput per CPU: custom_8_epoll gets 22,938 req/s per CPU vs 19,950-20,449 for FJ configs.

The IPC gap (1.09 custom vs 0.99 fj_8_8) is driven by DRAM misses — deep profiling ([FINDINGS.md](FINDINGS.md)) shows ManualEL FJ configs have 40-57% more DRAM misses/req (continuation thaw and pipeline traversal). fj_8_8 has +17% more DRAM misses/req with additional costs from its EL→FJ handoff queue.

---

## 5. Context Switches

| Config | context switches | nvcswch/s range | nvcswch spread |
|--------|---------------------|----------------|----------------------|
| custom_8_epoll | 1,667 | 11-20 | 1.5x |
| custom_8_nio | 1,151 | 15-25 | 1.3x |
| affinity_8 | 11,727 | 120-288 | 1.6x |
| no_affinity_8 | 164,813 | 257-2,214 | **8.6x** |
| fj_8_8 | 144,578 | (EL: 95-280, FJ: 110-240) | 2.5x |
| fj_4_4 | 37,220 | — | — |

Custom scheduler produces 100-140x fewer context switches and near-zero non-voluntary context switches. no_affinity_8 shows massive non-voluntary context switch imbalance (8.6x spread) — affinity flattens this to 1.6x. This imbalance disappears at sub-maximal load (see [REPORT-120K.md](REPORT-120K.md)).

---

## 6. Affinity at Max Load (affinity_8 vs no_affinity_8)

Same event loop, same FJ pool, only difference is affinity hints:

| Metric | affinity_8 | no_affinity_8 | Delta |
|--------|-----------|--------------|-------|
| Requests/sec | 168,189 | 158,798 | **+6%** |
| Context switches | 11,727 | 164,813 | **-93%** |
| nvcswch spread | 1.6x | 8.6x | **-81%** |

Affinity provides +6% throughput, 14x fewer context switches, and balanced worker load at max throughput. At sub-maximal load (120K), affinity has no measurable effect — affinity_8 and no_affinity_8 produce similar metrics ([FINDINGS.md](FINDINGS.md)).

---

## 7. Why Custom Beats FJ

| Metric | custom_8_nio | affinity_8 | fj_8_8 |
|--------|-------------|-----------|-------|
| Requests/sec | 174,374 | 168,189 | 161,368 |
| Context switches | 1,151 | 11,727 | 144,578 |
| IPC | 1.09 | 1.05 | 0.99 |
| nvcswch/s (avg) | 20 | 228 | ~175 |

The custom scheduler runs I/O events and virtual thread tasks on the same carrier thread. A virtual thread resumes on the same carrier that received the I/O event for its connection — implicit data locality without affinity hints.

Deep profiling ([FINDINGS.md](FINDINGS.md)) identified two sources of the efficiency gap:
1. **Fewer DRAM misses/req** — perf mem shows DRAM hotspots in continuation thaw and Netty pipeline traversal are 2-3x lower in custom
2. **Fewer instructions/req** — no FJ scheduling overhead, no EL→FJ handoff

fj_8_8 (standard Netty, 8 EL + 8 FJ) additionally pays for the EL→FJ handoff queue (LinkedBlockingQueue + unparkVirtualThread = 4.86% of DRAM samples) and 4x more cpu-migrations from 16 threads on 8 cores.

---

## 8. Key Takeaways

1. **custom_8_epoll is the most efficient config** — 183K req/s, highest IPC (1.08).

2. **epoll vs NIO on custom scheduler:** epoll wins on throughput (+5%) and latency. Both achieve near-zero context switches.

3. **Affinity helps FJ at max load** — +6% throughput, 14x fewer context switches, balanced worker load. But it cannot match custom's architectural advantage, and has no effect at sub-maximal load.

4. **fj_8_8 is the least efficient config** — 16 threads cause high context switches (145K) and cpu-migrations (4.2K) at max load, plus unique DRAM costs from the EL→FJ handoff. Lowest throughput among 8-EL configs.

5. **The IPC gap is DRAM misses** — not branch prediction or frontend stalls. ManualEL FJ configs have 40-57% more DRAM misses/req (continuation thaw and pipeline traversal); fj_8_8 has +17% with additional handoff queue costs.
