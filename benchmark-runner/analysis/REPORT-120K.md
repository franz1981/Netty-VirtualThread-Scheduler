# Sustained Load Efficiency Analysis — 120K req/s Fixed Load

## 1. Test Overview

**Objective:** Compare scheduler configurations under a fixed 120K req/s load (≈70% of max throughput) to measure per-request efficiency when the system has headroom.

| Parameter | Value |
|-----------|-------|
| Load | 120,000 req/s fixed rate |
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

### Configurations tested

| Config | Event Loop | Scheduler | I/O | Threads | Affinity | Poller |
|--------|-----------|-----------|-----|---------|----------|--------|
| **custom_8_nio** | VirtualMultithreadIoELG | NettyScheduler | NIO | 8 | structural | 3 |
| **affinity_8** | ManualIoELG | ForkJoinPool | NIO | 8 | roundRobin + inherit | 2 |
| **no_affinity_8** | ManualIoELG | ForkJoinPool | NIO | 8 | none | 2 |
| **fj_8_8** | MultiThreadIoELG | ForkJoinPool | NIO | 8+8 | none | 2 |

All configs achieved the target rate (119,695-119,810 req/s, within 0.1%).

---

## 2. Latency

At 120K, latency does not differentiate the configs. All four are identical up to p90 (~30.5-32.8ms = mock delay + overhead). p90+ tail latency is not stable on this machine across runs.

---

## 3. CPU Usage and Per-Request Cost

From deep profiling (6 perf stat passes, ≤5 HW events each). See [FINDINGS.md](FINDINGS.md) for full methodology.

|  | custom_8_nio | no_affinity_8 | affinity_8 | fj_8_8 |
|--|-------------|--------------|-----------|-------|
| **CPUs utilized** | **5.94** | 6.95 | 6.95 | 6.82 |
| **IPC** | 0.997 | 0.981 | 0.970 | 1.015 |
| **instructions/req** | **215,386** | 225,781 | 225,894 | 231,115 |
| **DRAM misses/req** | **2,041** | 3,202 | 2,858 | 2,390 |
| **context switches/10s** | **333K** | 1,033K | 1,000K | 1,071K |

custom_8_nio uses 13-15% less CPU to serve the same 120K req/s. Two sources ([FINDINGS.md](FINDINGS.md)):
1. Fewer instructions/req — no FJ scheduling overhead
2. Fewer DRAM misses/req — perf mem shows DRAM hotspots in continuation thaw and Netty pipeline traversal are 2-3x lower in custom

fj_8_8 has the highest IPC (1.015) but executes the most instructions/req (+7.3% vs custom). See section 7 for fj_8_8-specific costs.

---

## 4. Context Switches at 120K vs Max Load

| Config | ctx_sw (max load) | ctx_sw (120K) |
|--------|------------------|---------------|
| custom_8_nio | 1,151 | 333,017 |
| affinity_8 | 11,727 | 1,000,118 |
| no_affinity_8 | 164,813 | 1,033,285 |
| fj_8_8 | 144,578 | 1,071,409 |

At 120K, threads park between requests. All configs show dramatically more context switches. custom_8_nio still has the fewest — 3x fewer than others.

---

## 5. Non-voluntary Context Switch Balance

| Config | Avg nvcswch/s | max/min spread |
|--------|--------------|----------------|
| custom_8_nio | 291 | 1.14x |
| no_affinity_8 | 1,299 | 1.15x |
| affinity_8 | 1,556 | 1.09x |
| fj_8_8 (FJ workers) | 1,631 | 1.04x |

The non-voluntary context switch imbalance observed at max load has vanished. At max load, no_affinity showed an 8.6x spread. At 120K, all configs are balanced (1.04-1.15x). custom_8_nio still has 4-5x fewer non-voluntary context switches/s.

---

## 6. Affinity at 120K vs Max Load

**At 120K, affinity has no measurable effect.** affinity_8 vs no_affinity_8 metrics are within 2-3% — within run-to-run variance.

At 120K, carriers are not saturated (6.95 CPUs out of 8). affinity_8 and no_affinity_8 produce similar metrics at this load level (both 6.95 CPUs, DRAM misses/req within 12%). At max throughput, affinity_8 achieves 168K vs 159K for no_affinity_8. See [FINDINGS.md](FINDINGS.md) for affinity_8's 120K-vs-max comparison.

---

## 7. fj_8_8 at 120K

fj_8_8 (standard Netty, 8 EL + 8 FJ workers) has unique overhead at 120K:
- **+7.3% instructions/req** vs custom — EL→FJ handoff adds scheduling work
- **178K cpu-migrations** — 4-6x more than 8-thread configs (16 threads on 8 cores)
- **4.86% of DRAM samples** in LinkedBlockingQueue + unparkVirtualThread — the handoff queue cost, absent in ManualEL configs
- **6.13% continuation DRAM** — 3.5x custom, highest of all configs

At max throughput, migrations drop 97% and DRAM misses drop 47%, but IPC drops 14% (16 threads on 8 cores), giving fj_8_8 the lowest max throughput among 8-EL configs (161K vs 168K affinity, 174K custom).

---

## 8. Key Takeaways

1. **custom_8_nio is the most efficient at every load level** — 13-15% less CPU, fewer instructions/req (no FJ overhead), fewer DRAM misses/req.

2. **Affinity has no measurable effect at sub-maximal load.** affinity_8 and no_affinity_8 produce similar metrics at 120K. At max throughput, affinity_8 achieves higher throughput (168K vs 159K).

3. **Non-voluntary context switch imbalance disappears at sub-maximal load.** The 8.6x spread seen at max load drops to 1.04-1.15x at 120K.

4. **fj_8_8 is the least efficient 8-EL FJ config** — most instructions/req, most migrations, unique handoff queue DRAM costs, lowest max throughput among 8-EL configs.
