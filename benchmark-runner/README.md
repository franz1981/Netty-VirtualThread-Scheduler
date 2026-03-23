# Benchmark Runner

A comprehensive benchmarking module for testing handoff strategies between Netty event loops and virtual threads.

## Overview

This module provides:

1. **HandoffHttpServer** - An HTTP server that:
   - Receives requests on Netty event loops
   - Hands off to virtual threads (with configurable scheduler)
   - Makes blocking HTTP calls to a mock backend using JDK HttpClient
   - Parses JSON with Jackson
   - Returns response via event loop

2. **run-benchmark.sh** - A complete benchmarking script with:
   - Mock server management
   - CPU affinity control (taskset)
   - Warmup phase (no profiling)
   - Load generation via jbang wrk/wrk2
   - Async-profiler integration
   - pidstat monitoring
   - Duration configuration with validation

## Quick Start

```bash
# Set JAVA_HOME to your Java 27 build
export JAVA_HOME=/path/to/jdk

# Build the module (includes both MockHttpServer and HandoffHttpServer)
mvn package -pl benchmark-runner -am -DskipTests

# Run a basic benchmark
cd benchmark-runner/scripts
./run-benchmark.sh
```

## Build Details

Everything is packaged in a single JAR: `benchmark-runner/target/benchmark-runner.jar`

| Class | Description |
|-------|-------------|
| `io.netty.loom.benchmark.runner.MockHttpServer` | Backend mock server (think time + JSON response) |
| `io.netty.loom.benchmark.runner.HandoffHttpServer` | Server under test (handoff logic) |

The `run-benchmark.sh` script will automatically build the JAR if missing.

## Configuration

Configuration via CLI flags (preferred) or environment variables (fallback). CLI flags take precedence.

Run `./run-benchmark.sh --help` for the full list.

### Server
| CLI flag | Env var | Default | Description |
|----------|---------|---------|-------------|
| `--mode` | `SERVER_MODE` | NON_VIRTUAL_NETTY | Mode: NON_VIRTUAL_NETTY, REACTIVE, VIRTUAL_NETTY, NETTY_SCHEDULER |
| `--threads` | `SERVER_THREADS` | 2 | Number of event loop threads |
| `--mockless` | `SERVER_MOCKLESS` | false | Skip mock server; do Jackson work inline |
| `--io` | `SERVER_IO` | epoll | I/O type: epoll, nio, io_uring |
| `--poller-mode` | `SERVER_POLLER_MODE` | | jdk.pollerMode: 1, 2, or 3 |
| `--fj-parallelism` | `SERVER_FJ_PARALLELISM` | | ForkJoinPool parallelism |
| `--server-cpuset` | `SERVER_CPUSET` | 2,3 | CPU pinning |
| `--jvm-args` | `SERVER_JVM_ARGS` | | Additional JVM arguments |

### Mock Server
| CLI flag | Env var | Default | Description |
|----------|---------|---------|-------------|
| `--mock-port` | `MOCK_PORT` | 8080 | Mock server port |
| `--mock-think-time` | `MOCK_THINK_TIME_MS` | 1 | Simulated processing delay (ms) |
| `--mock-threads` | `MOCK_THREADS` | 1 | Number of Netty threads |
| `--mock-cpuset` | `MOCK_CPUSET` | 4,5 | CPU pinning |

### Load Generator
| CLI flag | Env var | Default | Description |
|----------|---------|---------|-------------|
| `--connections` | `LOAD_GEN_CONNECTIONS` | 100 | Number of connections |
| `--load-threads` | `LOAD_GEN_THREADS` | 2 | Number of threads |
| `--duration` | `LOAD_GEN_DURATION` | 30s | Test duration |
| `--rate` | `LOAD_GEN_RATE` | | Target rate for wrk2 (omit for max throughput) |
| `--load-cpuset` | `LOAD_GEN_CPUSET` | 0,1 | CPU pinning |

### Timing
| CLI flag | Env var | Default | Description |
|----------|---------|---------|-------------|
| `--warmup` | `WARMUP_DURATION` | 10s | Warmup duration |
| `--total-duration` | `TOTAL_DURATION` | 30s | Total test duration (steady-state >= 20s) |

### Profiling
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PROFILER` | false | Enable async-profiler |
| `ASYNC_PROFILER_PATH` | | Path to async-profiler installation |
| `PROFILER_EVENT` | cpu | Profiler event type |
| `PROFILER_OUTPUT` | profile.html | Output filename |

Profiling starts after `PROFILING_DELAY_SECONDS` and runs for `PROFILING_DURATION_SECONDS`.
Both the async-profiler and perf stat use `PROFILING_DELAY_SECONDS` and `PROFILING_DURATION_SECONDS`.

### JFR (Netty Loom events)
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_JFR` | false | Enable Netty Loom JFR events |
| `JFR_EVENTS` | all | Comma-separated list of events to enable |
| `JFR_OUTPUT` | netty-loom.jfr | JFR output filename |
| `JFR_RECORDING_NAME` | netty-loom-benchmark | JFR recording name |
| `JFR_SETTINGS_FILE` | | Path to a JFR settings file (.jfc). If set, overrides `JFR_EVENTS`. |
| `JFR_TIMELINE_OUTPUT` | netty-loom-timeline.jsonl | Timeline output filename (empty = skip export) |

Supported event names (short or full):
- `NettyRunIo` (`io.netty.loom.NettyRunIo`)
- `NettyRunTasks` (`io.netty.loom.NettyRunTasks`)
- `VirtualThreadTaskRuns` (`io.netty.loom.VirtualThreadTaskRuns`)
- `VirtualThreadTaskRun` (`io.netty.loom.VirtualThreadTaskRun`)
- `VirtualThreadTaskSubmit` (`io.netty.loom.VirtualThreadTaskSubmit`)

JFR uses the same profiling delay/duration settings to capture steady state.
The default settings file lives at `benchmark-runner/scripts/jfr/netty-loom.jfc`. Override it with `JFR_SETTINGS_FILE`.
When enabled, the benchmark exports a compact timeline JSONL alongside the JFR output (unless `JFR_TIMELINE_OUTPUT` is empty).

### pidstat
When enabled, pidstat always records three files: handoff server, mock server, and load generator.

| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PIDSTAT` | true | Enable pidstat collection |
| `PIDSTAT_INTERVAL` | 1 | Collection interval (seconds) |
| `PIDSTAT_OUTPUT` | pidstat.log | Output filename |
| `PIDSTAT_MOCK_OUTPUT` | pidstat-mock.log | Mock server output filename |
| `PIDSTAT_LOAD_GEN_OUTPUT` | pidstat-loadgen.log | Load generator output filename |
| `PIDSTAT_HANDOFF_DETAILED` | true | Include per-thread detail for handoff server |

### perf stat
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PERF_STAT` | false | Enable perf stat collection |
| `PERF_STAT_OUTPUT` | perf-stat.txt | Output filename |
| `PERF_STAT_ARGS` | | Extra perf stat arguments (passed as-is) |

perf stat uses `PROFILING_DELAY_SECONDS` and `PROFILING_DURATION_SECONDS`.

### General
| Variable | Default | Description |
|----------|---------|-------------|
| `JAVA_HOME` | | Path to Java installation (required) |
| `JAVA_OPTS` | -Xms1g -Xmx1g | JVM options |
| `OUTPUT_DIR` | ./benchmark-results | Output directory |
| `CONFIG_OUTPUT` | benchmark-config.txt | Configuration output filename |

## Example Runs

### Choosing CPU pinning with `lscpu -e`

Good benchmarking requires NUMA-aware CPU pinning. Start by inspecting your topology:

```bash
$ lscpu -e
CPU NODE SOCKET CORE L1d:L1i:L2:L3 ONLINE
  0    0      0    0 0:0:0:0          yes    # NUMA 0, physical core 0
  1    0      0    1 1:1:1:0          yes    # NUMA 0, physical core 1
  ...
  8    1      0    8 8:8:8:1          yes    # NUMA 1, physical core 8
  ...
 16    0      0    0 0:0:0:0          yes    # NUMA 0, SMT sibling of core 0
 17    0      0    1 1:1:1:0          yes    # NUMA 0, SMT sibling of core 1
```

Key rules:
- **Keep all benchmark components on the same NUMA node** to avoid cross-node memory latency
- **Use physical cores only** (avoid SMT siblings) for more stable results
- **Isolate noisy processes** (IDEs, browsers) on the other NUMA node

Example layout for a 16-core/2-NUMA system with 4 server threads:

| Component | CPUs | Rationale |
|-----------|------|-----------|
| Load generator | 0-1 | 2 physical cores, enough to saturate |
| Mock server | 2-3 | 2 physical cores for backend simulation |
| Handoff server | 4-7 | 4 physical cores, one per event loop thread |
| Other processes | 8-15 | Isolated on NUMA node 1 |

### NETTY_SCHEDULER with 4 threads

```bash
./run-benchmark.sh --mode NETTY_SCHEDULER --threads 4 --io nio \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --jvm-args "-Xms8g -Xmx8g" \
  --connections 10000 --load-threads 4 \
  --mock-think-time 30 --mock-threads 4 \
  --perf-stat
```

### Analyzing bottlenecks with perf stat

Use `--perf-stat` to get reliable hardware-level metrics. The `perf-stat.txt` output is the
ground truth for CPU utilization — pidstat per-thread numbers can be misleading with virtual threads.

```
Performance counter stats for process id '95868':

  39,741,757,754  task-clock           #  3.970 CPUs utilized
             806  context-switches     # 20.281 /sec
     199,114,762,646  instructions     #  1.17 insn per cycle
   1,338,722,757  branch-misses        #  3.08% of all branches
```

Key metrics to watch:
- **CPUs utilized**: how many cores the server is actually using (3.97 of 4 = fully saturated)
- **Context switches/sec**: lower is better; custom scheduler typically achieves 20-80/sec
- **IPC (insn per cycle)**: higher is better; >1.0 is good, <0.5 suggests memory stalls
- **Branch misses**: >5% suggests unpredictable control flow

If CPUs utilized equals your allocated core count, the server is CPU-bound — add more cores.
If context switches are high (>10K/sec), the scheduler or OS is thrashing.

pidstat is still useful for spotting **mock server or load generator bottlenecks** —
check `pidstat-mock.log` and `pidstat-loadgen.log` to ensure they aren't saturated.

### NON_VIRTUAL_NETTY (default mode)

```bash
./run-benchmark.sh --threads 4 \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --connections 10000 --mock-think-time 30
```

### VIRTUAL_NETTY mode

```bash
./run-benchmark.sh --mode VIRTUAL_NETTY --threads 4 --io nio \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --connections 10000 --mock-think-time 30
```

### Mockless mode (skip HTTP call to mock, inline Jackson work)

```bash
./run-benchmark.sh --mode NETTY_SCHEDULER --threads 4 --mockless \
  --server-cpuset "4-7" --load-cpuset "0-1" \
  --connections 10000
```

### With async-profiler

```bash
./run-benchmark.sh --mode NETTY_SCHEDULER --threads 4 \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --profiler --profiler-path /path/to/async-profiler \
  --warmup 15s --total-duration 45s
```

### Rate-limited test with wrk2

```bash
./run-benchmark.sh --mode NETTY_SCHEDULER --threads 4 \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --rate 120000 --connections 10000 --total-duration 60s --warmup 15s
```

### With JFR events

```bash
./run-benchmark.sh --mode NETTY_SCHEDULER --threads 4 \
  --server-cpuset "4-7" --mock-cpuset "2-3" --load-cpuset "0-1" \
  --jfr --jfr-events NettyRunIo,VirtualThreadTaskRuns
```

### Mixed: CLI flags + env vars

```bash
SERVER_JVM_ARGS="-XX:+PrintGCDetails" ./run-benchmark.sh --mode VIRTUAL_NETTY --threads 2
```

## Output

Results are saved to `./benchmark-results/` (configurable via `OUTPUT_DIR`):

- `wrk-results.txt` - Load generator output with throughput/latency
- `profile.html` - Flamegraph (if profiling enabled)
- `netty-loom.jfr` - JFR recording (if JFR events enabled)
- `netty-loom-timeline.jsonl` - Timeline export (if JFR enabled and `JFR_TIMELINE_OUTPUT` set)
- `pidstat.log` - Handoff server thread-level CPU usage (if pidstat enabled)
- `pidstat.log` includes per-thread command lines when `PIDSTAT_HANDOFF_DETAILED=true`. Note: Linux thread names are limited (comm is 15 chars), so very long JVM thread names may still appear truncated.
- `pidstat-mock.log` - Mock server thread-level CPU usage (if pidstat enabled)
- `pidstat-loadgen.log` - Load generator CPU usage (if pidstat enabled)
- `benchmark-config.txt` - Captured configuration summary for the run

## Architecture

```
┌─────────────────┐     ┌──────────────────────────┐     ┌─────────────────┐
│   wrk/wrk2      │────▶│   HandoffHttpServer      │────▶│  MockHttpServer │
│  (load gen)     │     │                          │     │                 │
└─────────────────┘     │  1. Receive on EL        │     │  Think time +   │
                        │  2. Handoff to VThread   │     │  JSON response  │
                        │  3. Blocking HTTP call   │     │                 │
                        │  4. Parse JSON (Jackson) │     └─────────────────┘
                        │  5. Write back on EL     │
                        └──────────────────────────┘
```

## Running Manually

### Mock Server

```bash
java -cp benchmark-runner/target/benchmark-runner.jar \
  io.netty.loom.benchmark.runner.MockHttpServer \
  8080 1    # port, thinkTimeMs (threads defaults to available processors)
```

### Handoff Server (custom scheduler mode)

```bash
java \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -XX:+UnlockExperimentalVMOptions \
  -XX:-DoJVMTIVirtualThreadTransitions \
  -Djdk.trackAllThreads=false \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler \
  -Djdk.pollerMode=3 \
  -cp benchmark-runner/target/benchmark-runner.jar \
  io.netty.loom.benchmark.runner.HandoffHttpServer \
  --port 8081 \
  --mock-url http://localhost:8080/fruits \
  --threads 2 \
  --mode netty_scheduler \
  --io epoll
```

### Handoff Server (default split topology)

```bash
java \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -XX:+UnlockExperimentalVMOptions \
  -XX:-DoJVMTIVirtualThreadTransitions \
  -Djdk.trackAllThreads=false \
  -cp benchmark-runner/target/benchmark-runner.jar \
  io.netty.loom.benchmark.runner.HandoffHttpServer \
  --port 8081 \
  --mock-url http://localhost:8080/fruits \
  --threads 2 \
  --io epoll
```
