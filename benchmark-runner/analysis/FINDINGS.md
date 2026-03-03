# CPU Efficiency Investigation — Findings

## Setup

Deep profiling with perf stat (6 passes, ≤5 HW events each, no multiplexing) and perf mem (AMD IBS sampling, ~300K samples, JIT symbol resolution via `libperf-jvmti.so` + `perf inject --jit`).

Four configs at 120K fixed-rate. All hit ~119.7K ± 0.1% across all passes. affinity_8 and fj_8_8 also profiled at max throughput.

All server cores (8-15) are on CCD1 sharing the same 32MB L3. L3 is the last level cache — an L3 miss goes to DRAM.

**Glossary:** EL = Event Loop (Netty I/O thread), FJ = ForkJoinPool (virtual thread scheduler), IPC = Instructions Per Cycle, nvcswch = non-voluntary context switches, IBS = Instruction Based Sampling (AMD hardware profiling, tags each sample with exact data source), CCD = Core Complex Die (8 cores sharing L3), DRAM = off-chip main memory.

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

### Where DRAM accesses happen (perf mem, IBS)

| Category | custom_8_nio | no_affinity_8 | affinity_8 | fj_8_8 |
|----------|-------------|--------------|-----------|-------|
| Continuation thaw/freeze | 1.76% | 3.71% | 3.50% | **6.13%** |
| channelRead (pipeline) | 0.03% | 2.95% | 3.65% | 0.55% |
| LinkedBlockingQueue | — | — | — | **2.84%** |
| unparkVirtualThread | — | — | — | **2.02%** |
| Total DRAM samples | 1,595 | 1,833 | 1,750 | 2,453 |

**Continuation thaw** accesses `stackChunkOopDesc` fields (`_bottom`, `_sp`, `_offset_of_stack`) and frozen stack frame data. The access pattern is pointer-chasing — each load depends on the previous load's result, so the CPU cannot prefetch ahead. IBS data source tagging shows these misses go straight from L2 to DRAM (near-zero L3 hits), meaning the data has been evicted from the entire cache hierarchy between thaw cycles.

**channelRead** traverses Netty's linked list of `ChannelHandlerContext` nodes — pointer-chasing, unprefetchable. custom_8_nio shows 0.03% DRAM here vs 2.95-3.65% for ManualEL FJ configs. fj_8_8 shows only 0.55% channelRead DRAM but has 4.86% in the handoff queue instead.

**fj_8_8-specific costs:** LinkedBlockingQueue (2.84%) and unparkVirtualThread (2.02%) are the EL→FJ handoff. These don't exist in ManualEL configs. Combined with 6.13% continuation DRAM (3.5x custom), fj_8_8 has the highest total DRAM samples (2,453). Its 16 threads on 8 cores also cause 178K cpu-migrations — 4-6x more than 8-thread configs. Each migration moves a thread to a core where its working set is not in L1/L2.

---

## Question 2: Why does affinity/FJ work better at max throughput than at 120K?

### affinity_8: 120K vs max

| Metric | @ 120K | @ max (152-163K*) | Delta |
|--------|--------|-------------|-------|
| CPUs utilized | 6.95 | 8.00 | saturated |
| IPC | 0.970 | 1.039 | **+7.1%** |
| instructions/req | 225,894 | 224,723 | -0.5% (same) |
| DRAM misses/req | 2,858 | 1,317 | **-54.0%** |
| context switches/10s | 1,000K | 10K | **-99%** |

Same instructions/req at both load levels. DRAM misses/req drop 54% and context switches drop 99% at max. Carriers are saturated at max (8.0 CPUs).

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
| Max throughput (wrk-only) | 174K | 168K | 159K | 161K |
| CPUs at 120K | 5.94 | 6.95 | 6.95 | 6.82 |
| DRAM misses/req @ 120K | 2,041 | 2,858 | 3,202 | 2,390 |
| instructions/req @ 120K | 215,386 | 225,894 | 225,781 | 231,115 |
| context switches @ 120K | 333K | 1,000K | 1,033K | 1,071K |
| Continuation DRAM % | 1.76% | 3.50% | 3.71% | 6.13% |
| Handoff queue DRAM % | — | — | — | 4.86% |

custom_8_nio uses 13-15% less CPU at 120K from fewer instructions/req (no FJ scheduling overhead) and fewer DRAM misses/req.

affinity_8 and no_affinity_8 have similar metrics at 120K. At max throughput (wrk-only), affinity_8 achieves 168K vs 159K for no_affinity_8.

fj_8_8 executes the most instructions/req (+7.3% vs custom), has unique DRAM costs from the EL→FJ handoff (4.86%), the highest continuation DRAM (6.13%), and 4-6x more cpu-migrations from 16 threads on 8 cores. At max throughput, migrations drop 97% and DRAM misses drop 47%, but IPC drops 14% (16 threads on 8 cores).

---

## Data quality

- All perf stat values are exact (no multiplexing) from 6-pass collection with ≤5 HW events per pass on AMD Zen4 (5 available GP counters after NMI watchdog).
- L3 miss rate from pass E: `cache-references` and `cache-misses` in the same run.
- perf mem uses AMD IBS sampling (~300K samples per run) with JIT symbol resolution.
- All 120K runs hit 119.7K ± 0.1%.
- *Max throughput during profiling runs is lower than wrk-only runs (REPORT.md) due to perf stat overhead. fj_8_8: 143-149K (vs 161K wrk-only). affinity_8: 152-163K (vs 168K wrk-only).
- perf c2c: ~760 HITMs at both load levels, ruling out false sharing.
