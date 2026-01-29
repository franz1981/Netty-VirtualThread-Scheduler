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

All configuration is via environment variables:

### Mock Server
| Variable | Default | Description |
|----------|---------|-------------|
| `MOCK_PORT` | 8080 | Mock server port |
| `MOCK_THINK_TIME_MS` | 1 | Simulated processing delay (ms) |
| `MOCK_THREADS` | auto | Number of Netty threads (empty = available processors) |
| `MOCK_TASKSET` | 4,5,6,7 | CPU affinity (e.g., "0-1") |

### Handoff Server
| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8081 | Server port |
| `SERVER_THREADS` | 2 | Number of event loop threads |
| `SERVER_REACTIVE` | false | Use reactive handler with Reactor |
| `SERVER_USE_CUSTOM_SCHEDULER` | false | Use custom Netty scheduler |
| `SERVER_IO` | epoll | I/O type: epoll, nio, or io_uring |
| `SERVER_NO_TIMEOUT` | false | Disable HTTP client timeout |
| `SERVER_TASKSET` | 2,3 | CPU affinity (e.g., "2-5") |
| `SERVER_JVM_ARGS` | | Additional JVM arguments |
| `SERVER_POLLER_MODE` | 3 | jdk.pollerMode value: 1, 2, or 3 |
| `SERVER_FJ_PARALLELISM` | | ForkJoinPool parallelism (empty = JVM default) |

### Load Generator
| Variable | Default | Description |
|----------|---------|-------------|
| `LOAD_GEN_CONNECTIONS` | 100 | Number of connections |
| `LOAD_GEN_THREADS` | 2 | Number of threads |
| `LOAD_GEN_RATE` | | Target rate (empty = max throughput with wrk) |
| `LOAD_GEN_TASKSET` | 0,1 | CPU affinity (e.g., "6-7") |
| `LOAD_GEN_URL` | http://localhost:8081/fruits | Target URL |

### Timing
| Variable | Default | Description |
|----------|---------|-------------|
| `WARMUP_DURATION` | 10s | Warmup duration (no profiling) |
| `TOTAL_DURATION` | 30s | Total test duration |

### Profiling
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PROFILER` | false | Enable async-profiler |
| `ASYNC_PROFILER_PATH` | | Path to async-profiler installation |
| `PROFILER_EVENT` | cpu | Profiler event type |
| `PROFILER_OUTPUT` | profile.html | Output filename |

### pidstat
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PIDSTAT` | false | Enable pidstat collection |
| `PIDSTAT_INTERVAL` | 1 | Collection interval (seconds) |
| `PIDSTAT_OUTPUT` | pidstat.log | Output filename |

### perf stat
| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PERF_STAT` | false | Enable perf stat collection |
| `PERF_STAT_OUTPUT` | perf-stat.txt | Output filename |

### General
| Variable | Default | Description |
|----------|---------|-------------|
| `JAVA_HOME` | | Path to Java installation (required) |
| `JAVA_OPTS` | -Xms1g -Xmx1g | JVM options |
| `OUTPUT_DIR` | ./benchmark-results | Output directory |

## Example Runs

### Basic comparison: custom vs default scheduler

```bash
# With custom scheduler
JAVA_HOME=/path/to/jdk \
SERVER_USE_CUSTOM_SCHEDULER=true \
./run-benchmark.sh

# With default scheduler  
JAVA_HOME=/path/to/jdk \
SERVER_USE_CUSTOM_SCHEDULER=false \
./run-benchmark.sh
```

### With CPU pinning

```bash
JAVA_HOME=/path/to/jdk \
MOCK_TASKSET="0" \
SERVER_TASKSET="1-4" \
LOAD_GEN_TASKSET="5-7" \
SERVER_THREADS=4 \
SERVER_USE_CUSTOM_SCHEDULER=true \
./run-benchmark.sh
```

### With profiling

```bash
JAVA_HOME=/path/to/jdk \
ENABLE_PROFILER=true \
ASYNC_PROFILER_PATH=/path/to/async-profiler \
PROFILER_EVENT=cpu \
SERVER_USE_CUSTOM_SCHEDULER=true \
WARMUP_DURATION=15s \
TOTAL_DURATION=45s \
./run-benchmark.sh
```

### Rate-limited test with wrk2

```bash
JAVA_HOME=/path/to/jdk \
LOAD_GEN_RATE=10000 \
LOAD_GEN_CONNECTIONS=200 \
TOTAL_DURATION=60s \
WARMUP_DURATION=15s \
./run-benchmark.sh
```

### With pidstat monitoring

```bash
JAVA_HOME=/path/to/jdk \
ENABLE_PIDSTAT=true \
PIDSTAT_INTERVAL=1 \
./run-benchmark.sh
```

## Output

Results are saved to `./benchmark-results/` (configurable via `OUTPUT_DIR`):

- `wrk-results.txt` - Load generator output with throughput/latency
- `profile.html` - Flamegraph (if profiling enabled)
- `pidstat.log` - Thread-level CPU usage (if pidstat enabled)

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

### Handoff Server (with custom scheduler)

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
  --use-custom-scheduler true \
  --io epoll
```

### Handoff Server (with default scheduler)

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
  --use-custom-scheduler false \
  --io epoll
```
