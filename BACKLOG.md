# Backlog

## 1. Kernel CPU accounting under-reports vs hardware PMU counters

**Priority**: Low (understanding only — use `perf stat` as ground truth)
**Status**: Root cause narrowed, further experiments possible

### Problem

The Linux kernel's CPU time accounting (`/proc/pid/stat`, `schedstat`) consistently under-reports
CPU utilization compared to `perf stat` (hardware PMU counters) for the Netty custom scheduler workload.

**Measured on NETTY_SCHEDULER with 4 carrier threads pinned to 4 physical cores (Ryzen 9 7950X):**

| Source | CPUs utilized | Notes |
|--------|--------------|-------|
| `perf stat` (PMU task-clock) | **3.96** | Hardware counter ground truth |
| `/proc/pid/stat` (utime+stime) | **3.19** | Kernel accounting |
| `schedstat` (sum of all thread run_ns) | **3.19** | CFS-level accounting |
| `pidstat` process-level | **2.84** | Even lower (pidstat's own sampling) |
| `pidstat` per-thread carrier sum | **2.72** | 4 x ~68% |

### Key findings

- **pidstat is NOT lying** — it faithfully reports what the kernel provides
- `/proc/pid/stat` and `schedstat` agree perfectly (both at 3.19 CPUs)
- The kernel itself under-counts by ~0.77 CPUs (19%) vs PMU hardware counters
- This is NOT caused by `CONFIG_TICK_CPU_ACCOUNTING` — the kernel uses
  `CONFIG_VIRT_CPU_ACCOUNTING_GEN=y` (full dyntick, precise at context switch boundaries)
- All 31 kernel-visible threads were accounted for — no "hidden threads" from
  `-Djdk.trackAllThreads=false` (VTs are invisible to the kernel by design, their CPU time
  is charged to carrier threads)

### Hypotheses for the 0.77 CPU gap

1. **Kernel scheduling overhead**: `__schedule()` / `finish_task_switch()` runs during
   thread transitions. Some of this CPU time may be attributed to idle/swapper rather
   than the thread being switched in/out.

2. **Interrupt handling**: hardware interrupts (NIC, timer) steal cycles from the process.
   `perf stat` counts all cycles on cores used by the process (task-clock includes time
   when PMU is inherited by children or interrupted contexts), while `/proc/stat` only
   counts time explicitly attributed to the thread.

3. **`task-clock` semantics**: `perf stat`'s `task-clock` measures wall-clock time that
   at least one thread of the process was running. With 4 threads on 4 cores, task-clock
   closely approximates 4.0 * elapsed. This includes interrupt handling time on those cores
   that `/proc/stat` charges elsewhere.

4. **Carrier thread park/unpark transitions**: even with VIRT_CPU_ACCOUNTING_GEN, the
   accounting happens at `schedule()` boundaries. CPU cycles consumed during the entry/exit
   paths of `LockSupport.park()` (before the actual `schedule()` call and after the wakeup)
   may be partially lost.

### Further experiments (if desired)

1. **Compare `perf stat -e task-clock` vs `perf stat -e cpu-clock`**: `task-clock` counts
   per-thread time, `cpu-clock` counts wall time. If they differ, it reveals interrupt overhead.

2. **Run with `nohz_full=4-7` (isolated CPUs)**: removes timer tick interrupts from server
   cores. If the gap shrinks, interrupt overhead is the cause.

3. **Spin-wait instead of park**: replace `LockSupport.park()` with `Thread.onSpinWait()`
   in `FifoEventLoopScheduler`. If gap shrinks, park/unpark accounting is lossy.

4. **Check `/proc/interrupts`** delta during benchmark: quantify how many interrupts hit
   cores 4-7 and estimate their CPU cost.

5. **`perf stat` per-thread (`-t TID`)** for each carrier: compare PMU task-clock per
   carrier vs schedstat per carrier to see if the gap is evenly distributed.

### Conclusion

For benchmarking purposes, **always use `perf stat` as the ground truth** for CPU utilization.
pidstat is still useful for relative thread balance analysis and for monitoring non-server
components (mock server, load generator) where the gap is less significant.

---

## 2. Add spin-wait phase before carrier thread parking

**Priority**: Medium (performance optimization)
**Status**: Not started

In `FifoEventLoopScheduler.virtualThreadSchedulerLoop()`, the carrier thread parks immediately
when the queue drains. Adding a brief spin-wait phase (e.g., 100-1000 iterations of
`Thread.onSpinWait()`) before calling `LockSupport.park()` could:

- Reduce wake-up latency for incoming work (avoid kernel schedule/deschedule)
- Reduce context switch count (currently ~20/sec, could go to near-zero)
- Trade-off: slightly higher idle CPU consumption

### Key file
- `core/src/main/java/io/netty/loom/FifoEventLoopScheduler.java` line ~199
