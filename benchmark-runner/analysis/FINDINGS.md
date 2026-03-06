# CPU Efficiency Investigation — Findings

## Setup

Deep profiling with perf stat (6 passes, ≤5 HW events each, no multiplexing) and perf mem (AMD IBS sampling, ~300K samples, JIT symbol resolution via `libperf-jvmti.so` + `perf inject --jit`).

Four configs at 120K fixed-rate. All hit ~119.7K ± 0.1% across all passes. affinity_8 and fj_8_8 also profiled at max throughput.

All server cores (8-15) are on CCD1 sharing the same 32MB L3. L3 is the last level cache — an L3 miss goes to DRAM.

> **Glossary**
> - **EL** — Event Loop (Netty I/O thread)
> - **FJ** — ForkJoinPool (virtual thread scheduler)
> - **IPC** — Instructions Per Cycle
> - **nvcswch** — non-voluntary context switches
> - **IBS** — Instruction Based Sampling (AMD hardware profiling, tags each sample with exact data source)
> - **CCD** — Core Complex Die (8 cores sharing L3)
> - **DRAM** — off-chip main memory

---

## Question 1: Why does custom_8_nio use less CPU than FJ-based configs at 120K?

### Per-request metrics

|  | custom_8_nio | no_affinity_8 | affinity_8 | fj_8_8 |
|--|-------------|--------------|-----------|-------|
| CPUs utilized | 5.94 | 6.95 | 6.95 | 6.82 |
| IPC | 0.997 | 0.981 | 0.970 | 1.015 |
| instructions/req | 215,386 | 225,781 | 225,894 | 231,115 |
| DRAM misses/req | 2,041 | 3,202 | 2,858 | 2,390 |
| context switches/10s | 333K | 1,033K | 1,000K | 1,071K |
| cpu-migrations/10s | 46K | 35K | 31K | **178K** |

Delta vs custom_8_nio:

| Metric | no_affinity_8 | affinity_8 | fj_8_8 |
|--------|--------------|-----------|-------|
| instructions/req | +4.8% | +4.9% | **+7.3%** |
| DRAM misses/req | **+56.9%** | **+40.0%** | +17.1% |
| context switches | **3.1x** | **3.0x** | **3.2x** |
| cpu-migrations | -26% | -34% | **+281%** |

Two sources of the CPU gap: FJ configs execute more instructions/req (scheduling overhead) and have more DRAM misses/req.

### Where DRAM accesses happen at 120K (perf mem, IBS)

The following table shows IBS sample counts tagged as "RAM hit" per category. These are not absolute DRAM miss counts — IBS samples memory accesses at a fixed rate and tags each sample with its data source (L1/L2/L3/RAM). The relative distribution across configs is valid (same sampling rate, same duration, same throughput).

| Category | custom_8_nio | no_affinity_8 | affinity_8 | fj_8_8 |
|----------|:-----------:|:------------:|:---------:|:-----:|
| Netty pipeline (write, channelRead, handler) | 171 | 386 | 311 | 393 |
| Continuation (thaw, prepare, StackChunkFrame, run) | 25 | 85 | 60 | 153 |
| HTTP client (MainClientExec, KeepAlive, HttpHost, ...) | 156 | 233 | 211 | 200 |
| Kernel networking (sock_poll, epoll, tcp_*, lock_sock) | 421 | 459 | 459 | 635 |
| FJ handoff (LinkedBlockingQueue, unparkVirtualThread) | — | — | — | 174 |
| Other | 822 | 670 | 709 | 898 |
| **Total** | **1,595** | **1,833** | **1,750** | **2,453** |

The DRAM increase in FJ configs is broad — not concentrated in a few hotspots. Every category shows more DRAM samples than custom: Netty pipeline (+82-130%), continuation (+140-512%), HTTP client (+28-49%), kernel networking (+9-51%). fj_8_8 additionally pays 174 samples for the EL→FJ handoff (LinkedBlockingQueue + unparkVirtualThread), absent in all other configs.

**Continuation thaw** accesses `stackChunkOopDesc` fields via pointer-chasing — each load depends on the previous load's result, so the CPU cannot prefetch ahead. IBS data source tagging shows these misses go straight from L2 to DRAM (near-zero L3 hits), meaning the data has been evicted from the entire cache hierarchy between thaw cycles.

**Netty pipeline** traverses a linked list of `ChannelHandlerContext` nodes — also pointer-chasing. The specific functions differ between configs (e.g. `CombinedChannelDuplexHandler.write` appears only in affinity_8, `SimpleChannelInboundHandler.channelRead` only in no_affinity_8) but the total pipeline DRAM cost is consistently 2-2.3x higher than custom across all FJ configs.

**fj_8_8** has the highest total DRAM samples (2,453, +54% vs custom). Its 16 threads on 8 cores cause 178K cpu-migrations — 4-6x more than 8-thread configs. Each migration moves a thread to a core where its working set is not in L1/L2.

---

## Question 2: What changes between 120K and max throughput?

### affinity_8: 120K vs max

| Metric | @ 120K | @ max (152-163K*) | Delta |
|--------|--------|-------------|-------|
| CPUs utilized | 6.95 | 8.00 | saturated |
| IPC | 0.970 | 1.039 | **+7.1%** |
| instructions/req | 225,894 | 224,723 | -0.5% (same) |
| DRAM misses/req | 2,858 | 1,317 | **-54.0%** |
| context switches/10s | 1,000K | 10K | **-99%** |

Same instructions/req at both load levels. At max, carriers are saturated (8.0 CPUs), DRAM misses/req drop 54%, and context switches drop 99%. No IBS data at max to show where the DRAM reduction comes from.

### fj_8_8: 120K vs max

| Metric | @ 120K | @ max (143-149K*) | Delta |
|--------|--------|-------------|-------|
| CPUs utilized | 6.82 | 7.90 | near-saturated |
| IPC | 1.015 | 0.870 | **-14.3%** |
| instructions/req | 231,115 | 199,160 | **-13.8%** |
| DRAM misses/req | 2,390 | 1,260 | **-47.3%** |
| context switches/10s | 1,071K | 170K | **-84.2%** |
| cpu-migrations/10s | 178K | 6K | **-96.6%** |

Different pattern from affinity_8: instructions/req drop 14% at max while IPC also drops 14%, canceling out. The 16 threads stop migrating (-97%) and DRAM misses drop 47%. IPC drops with 16 threads on 8 cores.

### L3 miss rate (same-run)

| Config | L3 miss/req | L3 miss rate |
|--------|------------|-------------|
| custom_8_nio @ 120K | 2,145 | **11.8%** |
| no_affinity_8 @ 120K | 2,590 | **13.1%** |
| affinity_8 @ 120K | 2,576 | **14.0%** |
| fj_8_8 @ 120K | 2,455 | **14.2%** |
| affinity_8 @ max | 1,554 | **8.7%** |
| fj_8_8 @ max | 1,252 | **7.4%** |

L3 miss rate nearly doubles from max to 120K for both configs. At 120K, IBS shows continuation data is not in any cache level by the time it's needed again.

---

## Summary

|  | custom_8_nio | affinity_8 | no_affinity_8 | fj_8_8 |
|--|-------------|-----------|--------------|-------|
| Max throughput | 174K | 168K | 159K | 161K |
| CPUs at 120K | 5.94 | 6.95 | 6.95 | 6.82 |
| DRAM misses/req @ 120K | 2,041 | 2,858 | 3,202 | 2,390 |
| instructions/req @ 120K | 215,386 | 225,894 | 225,781 | 231,115 |
| context switches @ 120K | 333K | 1,000K | 1,033K | 1,071K |
| IBS DRAM samples @ 120K | 1,595 | 1,750 | 1,833 | 2,453 |

custom_8_nio uses 13-15% less CPU at 120K, executing fewer instructions/req and fewer DRAM misses/req. The DRAM increase in FJ configs is broad — every category (Netty pipeline, continuation, HTTP client, kernel networking) shows more IBS DRAM samples than custom.

affinity_8 and no_affinity_8 have similar metrics at 120K. At max throughput, affinity_8 achieves 168K vs 159K for no_affinity_8.

fj_8_8 executes the most instructions/req (+7.3% vs custom), has the most IBS DRAM samples (2,453, +54% vs custom) including 174 from the EL→FJ handoff (absent in other configs), and 4-6x more cpu-migrations from 16 threads on 8 cores.

At max throughput, both affinity_8 and fj_8_8 show substantially fewer DRAM misses/req (47-54% less) and context switches (84-99% less) compared to their 120K values. L3 miss rate drops from 14% to 7-9%. We do not have IBS data at max to identify where the DRAM reduction occurs.

---

## Data quality

- All perf stat values are exact (no multiplexing) from 6-pass collection with ≤5 HW events per pass on AMD Zen4 (5 available GP counters after NMI watchdog).
- L3 miss rate from pass E: `cache-references` and `cache-misses` in the same run.
- perf mem uses AMD IBS sampling (~300K samples per run) with JIT symbol resolution.
- All 120K runs hit 119.7K ± 0.1%.
- *Max throughput during profiling runs is lower than REPORT.md due to perf stat overhead. fj_8_8: 143-149K (vs 161K). affinity_8: 152-163K (vs 168K).
- perf c2c: ~760 HITMs at both load levels, ruling out false sharing.
