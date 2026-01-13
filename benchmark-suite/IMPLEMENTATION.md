# Benchmark Suite Implementation Summary

## Overview

This benchmark suite implements a comprehensive testing framework for comparing the custom NettyScheduler against the default JVM virtual thread scheduler. The implementation follows the requirements specified in the issue and provides a realistic HTTP/binary server scenario.

## Architecture Components

### 1. Binary Backend Server (`BinaryServer.java`)

**Purpose**: Lightweight backend service that responds to binary protocol requests.

**Key Features**:
- Uses Netty 4.2 with length-prefixed binary protocol (4-byte length header)
- Single event loop for minimal overhead
- Pre-generates and caches response data (User array in binary format)
- Configurable think time to simulate processing delay
- Format: 4-byte count + N × 4-byte user IDs (big-endian)

**Configuration**:
- `BINARY_PORT`: Port to listen on (default: 9090)
- `THINK_TIME_MS`: Artificial delay before responding (default: 0)
- `USER_COUNT`: Number of User objects in response (default: 100)

### 2. HTTP Frontend Server (`HttpServer.java`)

**Purpose**: Main server that handles HTTP requests and demonstrates scheduler differences.

**Key Features**:
- Uses Netty 4.2 with HTTP codec
- Configurable number of event loops
- **Scheduler selection**: Custom NettyScheduler or default virtual threads
- Connection-scoped binary client (persistent, blocking HTTP connection)
- Virtual thread offloading for request processing
- Jackson-based JSON serialization to ByteBuf

**Request Flow**:
1. HTTP request arrives on event loop
2. Get or create persistent binary client for this connection
3. Offload to virtual thread (using configured scheduler)
4. Virtual thread performs blocking call to binary server
5. Parse binary response into User objects
6. Serialize to JSON using Jackson
7. Send HTTP response back to client

**Configuration**:
- `HTTP_PORT`: Port to listen on (default: 8080)
- `BACKEND_HOST`: Binary server hostname (default: localhost)
- `BACKEND_PORT`: Binary server port (default: 9090)
- `SCHEDULER`: "custom" or "default" (default: custom)
- `EVENT_LOOPS`: Number of Netty event loops (default: 2)

**Scheduler Implementations**:
- **Custom**: Uses `VirtualMultithreadIoEventLoopGroup` with NettyScheduler
  - Virtual threads inherit event loop scheduler
  - Better cache locality and fewer context switches
  - Virtual threads run on same carrier as event loop when possible
- **Default**: Uses `MultiThreadIoEventLoopGroup` with `Thread.ofVirtual().factory()`
  - Standard JVM virtual thread scheduler
  - Baseline for comparison

### 3. Data Model (`User.java`)

Simple POJO with a single integer `id` field, serialized to JSON.

### 4. Orchestration Scripts

#### `benchmark.sh`
Main benchmark orchestration script based on the Quarkus profiling workshop pattern.

**Features**:
- Starts binary server first, then HTTP server
- Verifies both servers are running
- Uses Hyperfoil wrk/wrk2 for load generation
- CPU affinity support (Linux only via taskset)
- Monitoring with pidstat (Linux)
- Optional async-profiler integration
- Saves timestamped results

**Options**:
- `-d SECONDS`: Duration (default: 40s)
- `-c CONNECTIONS`: Concurrent connections (default: 10)
- `-r RATE`: Request rate (0=unlimited, default: 0)
- `-s SCHEDULER`: "custom" or "default" (default: custom)
- `-e LOOPS`: Event loops (default: 2)
- `-t MS`: Binary server think time (default: 0)
- `-p`: Enable async-profiler

**Workflow**:
1. Install jbang tools (wrk, wrk2, optional ap-loader)
2. Build if needed
3. Start binary server on configured CPUs
4. Start HTTP server on configured CPUs
5. Verify both servers are running
6. Start load generator
7. Warmup period (40% of duration)
8. Start pidstat monitoring
9. Optional async-profiler for profiling period (50% of duration)
10. Wait for completion
11. Save results to `results/` directory

#### `compare.sh`
Convenience script that runs benchmarks with both schedulers sequentially.

**Usage**:
```bash
./compare.sh [duration] [connections] [rate]
```

**Features**:
- Runs custom scheduler benchmark
- Waits 10 seconds
- Runs default scheduler benchmark
- Extracts and compares key metrics
- Shows performance summary

#### `docker-benchmark.sh`
Docker-based benchmark runner for containerized environments.

**Features**:
- Builds Docker images automatically
- Starts appropriate containers based on scheduler choice
- Runs load test against containerized servers
- Shows container resource usage
- Automatic cleanup

### 5. Docker Support

#### `Dockerfile.http-server`
HTTP server container using `shipilev/openjdk:loom` base image.

#### `Dockerfile.binary-server`
Binary server container using `shipilev/openjdk:loom` base image.

#### `docker-compose.yml`
Orchestrates both servers with:
- Host networking for minimal overhead
- CPU affinity via cpuset
- Environment-based configuration
- Separate profiles for custom/default schedulers

## Dependencies

**Core**:
- Netty 4.2.9.Final (HTTP, NIO, length-prefixed codec)
- Jackson 2.18.2 (JSON serialization)
- Apache HttpComponents Client 5.4.1 (not currently used, can be alternative to Socket)

**Tools**:
- jbang (for wrk, wrk2, ap-loader)
- Maven 3.6+
- Docker and Docker Compose (optional)

**Monitoring**:
- pidstat (Linux sysstat package)
- async-profiler (optional)
- perf (optional)

## Build System

Maven multi-module project with shade plugin to create executable JARs:
- `http-server.jar`: HTTP frontend server
- `binary-server.jar`: Binary backend server

Both JARs include all dependencies and can run standalone.

## Usage Patterns

### Local Development
```bash
cd benchmark-suite/scripts
./benchmark.sh -s custom -d 60 -c 20
./benchmark.sh -s default -d 60 -c 20
```

### Comparison
```bash
./compare.sh 60 20
```

### Docker
```bash
docker compose -f docker/docker-compose.yml up binary-server http-server-custom
# In another terminal
jbang wrk@hyperfoil -c 20 -d 60s http://localhost:8080/
```

### Profiling
```bash
./benchmark.sh -p -d 120 -c 50
```

## Performance Metrics

**Primary Metrics**:
- Throughput (requests/sec)
- Latency distribution (avg, p50, p90, p99)
- CPU usage (% from pidstat)
- Memory usage (RSS from pidstat)

**Secondary Metrics** (when available):
- Context switches (pidstat -w)
- Cache misses (perf stat)
- Flamegraphs (async-profiler)

## Expected Performance Characteristics

### Custom Scheduler
- **Pros**: Better cache locality, fewer context switches, more predictable latency
- **Cons**: May saturate carrier threads under extreme load
- **Best for**: Moderate concurrency with short-to-medium blocking operations

### Default Scheduler
- **Pros**: Better work distribution, higher peak throughput potential
- **Cons**: More context switches, potential cache misses
- **Best for**: High concurrency or long-running blocking operations

## Files Created

```
benchmark-suite/
├── .gitignore
├── README.md (comprehensive guide)
├── QUICKSTART.md (quick reference)
├── pom.xml
├── docker/
│   ├── Dockerfile.binary-server
│   ├── Dockerfile.http-server
│   └── docker-compose.yml
├── scripts/
│   ├── benchmark.sh (main orchestrator)
│   ├── compare.sh (comparison runner)
│   └── docker-benchmark.sh (Docker runner)
└── src/main/java/io/netty/loom/benchmark/suite/
    ├── BinaryServer.java
    ├── HttpServer.java
    └── User.java
```

## Testing Strategy

The benchmark suite itself is the test. To validate:

1. **Build test**: `mvn clean install -DskipTests`
2. **Server startup test**: Start servers manually and verify with curl
3. **Load test**: Run benchmark.sh and verify successful completion
4. **Comparison test**: Run compare.sh and verify both schedulers work
5. **Docker test**: Run docker-benchmark.sh and verify containerized operation

## Integration with Main Project

- Added `benchmark-suite` module to parent pom.xml
- Updated main README.md to reference the benchmark suite
- Follows existing code style and license headers
- Uses same Netty and Java versions as other modules

## Future Enhancements

Potential improvements (not implemented):
- Automated result analysis and visualization
- Support for multiple HTTP clients (currently uses basic Socket)
- Configurable request/response sizes
- Multiple endpoint support
- Integration with CI/CD for regression testing
- Support for qDup-based orchestration (as mentioned in agent instructions)
