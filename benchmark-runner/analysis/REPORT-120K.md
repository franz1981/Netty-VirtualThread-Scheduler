# Sustained Load Efficiency Analysis — 120K req/s Fixed Load

## 1. Test Overview

**Objective:** Compare scheduler configurations under a fixed 120K req/s load (≈70% of max throughput) to measure per-request efficiency when the system has headroom.

| Parameter | Value |
|-----------|-------|
| Target rate | 120,000 req/s |
| Connections | 10,000 |
| Mock think time | 30ms |
| CPU pinning | server=8-15, mock=4-7, loadgen=0-3 |

### Configurations tested

| Config | Event Loop | Scheduler | Threads | Poller | Affinity |
|--------|-----------|-----------|---------|--------|----------|
| **custom_8_nio** | VirtualMultithreadIoELG | NettyScheduler | 8 | 3 | structural |
| **affinity_8** | ManualIoELG | ForkJoinPool | 8 | 2 | roundRobin + inherit |
| **no_affinity_8** | ManualIoELG | ForkJoinPool | 8 | 2 | none |
| **fj_8_8** | MultiThreadIoELG | ForkJoinPool | 8+8 | 2 | none |

All configs achieved the target rate (119,695-119,810 req/s, within 0.1%).

---

## 2. Latency

At 120K, latency does not differentiate the configs. All four are identical up to p90 (~30.5-32.8ms = mock delay + overhead). p90+ tail latency is not stable on this machine across runs.

---

## 3. CPU Usage and Per-Request Cost (exact, 0% multiplexing)

From deep profiling (6 perf stat passes, ≤5 HW events each). See [FINDINGS.md](investigation/FINDINGS.md) for full methodology.

|  | custom_8_nio | no_affinity_8 | affinity_8 | fj_8_8 |
|--|-------------|--------------|-----------|-------|
| **CPUs utilized** | **5.94** | 6.95 | 6.95 | 6.82 |
| **IPC** | 0.997 | 0.981 | 0.970 | 1.015 |
| **instructions/req** | **215,386** | 225,781 | 225,894 | 231,115 |
| **DRAM misses/req** | **2,041** | 3,202 | 2,858 | 2,390 |
| **L3 miss%** | **11.8%** | 13.1% | 14.0% | 14.2% |
| **context switches/10s** | **333K** | 1,033K | 1,000K | 1,071K |
| **cpu-migrations/10s** | 46K | 35K | 31K | **178K** |

custom_8_nio uses 13-15% less CPU to serve the same 120K req/s. Two sources ([FINDINGS.md](investigation/FINDINGS.md)):
1. Fewer instructions/req — no FJ scheduling overhead
2. Fewer DRAM misses/req — same carrier handles the same connection, keeping continuation stack chunks and Netty pipeline objects warm in cache

fj_8_8 has the highest IPC (1.015) but executes the most instructions/req (+7.3% vs custom) and has 4-6x more cpu-migrations from 16 threads on 8 cores.

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

## 5. nvcswch Balance

| Config | Avg nvcswch/s | max/min spread |
|--------|--------------|----------------|
| custom_8_nio | 291 | 1.14x |
| no_affinity_8 | 1,299 | 1.15x |
| affinity_8 | 1,556 | 1.09x |
| fj_8_8 (FJ workers) | 1,631 | 1.04x |

The nvcswch imbalance observed at max load has vanished. At max load, no_affinity showed an 8.6x nvcswch spread. At 120K, all configs are balanced (1.04-1.15x). custom_8_nio still has 4-5x fewer nvcswch/s.

---

## 6. Affinity at 120K vs Max Load

**At 120K, affinity has no measurable effect.** affinity_8 vs no_affinity_8 metrics are within 2-3% — within run-to-run variance.

At 120K, carriers are not saturated (6.95 CPUs out of 8). Carriers idle between requests. During idle time, continuation stack chunk data gets evicted from the 32MB shared L3. The next thaw fetches from DRAM regardless of whether it's the same carrier or a different one.

At max throughput, affinity makes a difference: carriers run continuously, DRAM misses/req drop 54%, context switches drop 99%. See [FINDINGS.md](investigation/FINDINGS.md) for the 120K-vs-max comparison.

---

## 7. fj_8_8 at 120K

fj_8_8 (standard Netty, 8 EL + 8 FJ workers) has unique overhead at 120K:
- **+7.3% instructions/req** vs custom — EL→FJ handoff adds scheduling work
- **178K cpu-migrations** — 4-6x more than 8-thread configs (16 threads on 8 cores)
- **4.86% of DRAM samples** in LinkedBlockingQueue + unparkVirtualThread — the handoff queue cost, absent in ManualEL configs
- **6.13% continuation DRAM** — 3.5x custom, highest of all configs

At max throughput, migrations drop 97% and DRAM misses drop 47%, but IPC drops 14% from time-slicing 16 threads on 8 cores, giving fj_8_8 the lowest max throughput of the 8-core configs (161K vs 168K affinity, 174K custom).

---

## 8. Key Takeaways

1. **custom_8_nio is the most efficient at every load level** — 13-15% less CPU, fewer instructions/req (no FJ overhead), fewer DRAM misses/req (implicit data locality).

2. **Affinity has no effect at sub-maximal load.** Carriers idle and cache data goes cold regardless. Affinity only helps when carriers are saturated (max throughput), where it cuts DRAM misses by 54%.

3. **nvcswch imbalance disappears at sub-maximal load.** The 8.6x spread seen at max load drops to 1.04-1.15x at 120K.

4. **fj_8_8 is the least efficient FJ config** — most instructions/req, most migrations, unique handoff queue DRAM costs, lowest max throughput.
