# Benchmark Suite for Netty VirtualThread Scheduler

This benchmark suite compares the performance of the custom NettyScheduler against the default JVM virtual thread scheduler in a realistic HTTP/binary server scenario.

## Architecture

The benchmark consists of three main components:

### 1. HTTP Frontend Server (Server 1)
- Listens for HTTP requests on port 8080 (configurable)
- Uses Netty 4.2 with HTTP codec
- Configurable number of event loops
- **Scheduler selection**: Can use either custom NettyScheduler or default VirtualThreadScheduler
- For each HTTP connection:
  - Creates a persistent blocking HTTP client connection to the binary backend server
  - Offloads request processing to a virtual thread (using the selected scheduler)
  - The virtual thread issues a blocking request to the binary server
  - Parses the binary response into User objects
  - Serializes to JSON using Jackson
  - Returns JSON response to the client

### 2. Binary Backend Server (Server 2)
- Listens on port 9090 (configurable) using length-prefixed binary protocol
- Uses a single Netty event loop for minimal overhead
- Pre-generates and caches the response payload (array of User objects in binary format)
- Supports configurable "think time" to simulate processing delay
- Very lightweight by design

### 3. Load Generator
- Uses Hyperfoil's wrk or wrk2 (via jbang)
- Configurable number of connections and request rate
- Drives load against the HTTP frontend server

## Components

### User Model
Simple data model representing a user with an integer ID:
```java
public class User {
    private int id;
}
```

### Binary Protocol
The binary backend uses a length-prefixed protocol:
- 4-byte length header (big-endian)
- Payload format: 4-byte user count + N × 4-byte user IDs

### HTTP Server Modes
- **Custom Scheduler**: Uses `VirtualMultithreadIoEventLoopGroup` with NettyScheduler
- **Default Scheduler**: Uses standard `MultiThreadIoEventLoopGroup` with default virtual threads

## Prerequisites

1. **Java with Project Loom support** (Java 21+ with virtual threads)
   - Recommended: Use the Shipilev Loom build from https://builds.shipilev.net/openjdk-jdk-loom/
   - Or use the provided dev container with the Loom JDK

2. **Maven** 3.6+

3. **jbang** (will be auto-installed if not present)
   ```bash
   curl -Ls https://sh.jbang.dev | bash -s - app setup
   ```

4. **Optional**: Docker and Docker Compose for containerized runs

5. **Optional (Linux)**: pidstat (part of sysstat package) for resource monitoring
   ```bash
   sudo apt-get install sysstat  # Ubuntu/Debian
   sudo yum install sysstat       # RHEL/CentOS
   ```

## Building

Build the entire project including the benchmark suite:

```bash
cd /path/to/Netty-VirtualThread-Scheduler
mvn clean install -DskipTests
```

This creates two executable JARs:
- `benchmark-suite/target/http-server.jar`
- `benchmark-suite/target/binary-server.jar`

## Running Locally

### Smoke Test (Quick Validation)

Run a quick 5-second smoke test to verify the suite works correctly:

```bash
cd benchmark-suite/scripts
./smoke-test.sh
```

This script:
- Tests both custom and default schedulers
- Runs for 5 seconds each
- Verifies throughput is produced
- Suitable for CI/CD validation

### Quick Start

Run a benchmark with default settings (custom scheduler):

```bash
cd benchmark-suite/scripts
./benchmark.sh
```

### Configuration Options

```bash
./benchmark.sh -h
```

Available options:
- `-h`: Show help
- `-d SECONDS`: Duration of load test (default: 40s)
- `-c CONNECTIONS`: Number of concurrent connections (default: 10)
- `-r RATE`: Request rate in req/s (0 = unlimited, default: 0)
- `-s SCHEDULER`: Scheduler type: 'custom' or 'default' (default: custom)
- `-e LOOPS`: Number of event loops for HTTP server (default: 2)
- `-t MS`: Think time in milliseconds for binary server (default: 0)
- `-p`: Enable async-profiler

### Example Usage

Compare custom scheduler vs default scheduler:

```bash
# Run with custom scheduler
./benchmark.sh -s custom -d 60 -c 20

# Run with default scheduler
./benchmark.sh -s default -d 60 -c 20
```

Run with fixed request rate and profiling:

```bash
./benchmark.sh -r 10000 -c 50 -d 120 -p
```

Run with higher think time to simulate backend latency:

```bash
./benchmark.sh -t 10 -d 60
```

## Running with Docker

### Build Docker Images

```bash
cd benchmark-suite
mvn clean package -DskipTests
docker compose -f docker/docker-compose.yml build
```

### Run with Custom Scheduler

```bash
docker compose -f docker/docker-compose.yml up binary-server http-server-custom
```

The HTTP server will be available at http://localhost:8080

### Run with Default Scheduler (for comparison)

```bash
docker compose -f docker/docker-compose.yml up binary-server http-server-default
```

The HTTP server will be available at http://localhost:8081

### Manual Container Runs

You can also run containers manually with custom CPU affinity:

```bash
# Start binary server on CPU 2
docker run --rm --network host --cpuset-cpus="2" \
  -e BINARY_PORT=9090 -e THINK_TIME_MS=0 -e USER_COUNT=100 \
  benchmark-binary-server

# Start HTTP server with custom scheduler on CPUs 0,1
docker run --rm --network host --cpuset-cpus="0,1" \
  -e HTTP_PORT=8080 -e SCHEDULER=custom -e EVENT_LOOPS=2 \
  -e BACKEND_HOST=localhost -e BACKEND_PORT=9090 \
  benchmark-http-server
```

## Configuration Parameters

### HTTP Server
- `HTTP_PORT`: Port to listen on (default: 8080)
- `BACKEND_HOST`: Binary server hostname (default: localhost)
- `BACKEND_PORT`: Binary server port (default: 9090)
- `SCHEDULER`: Scheduler type - "custom" or "default" (default: custom)
- `EVENT_LOOPS`: Number of Netty event loops (default: 2)

### Binary Server
- `BINARY_PORT`: Port to listen on (default: 9090)
- `THINK_TIME_MS`: Artificial delay before responding in milliseconds (default: 0)
- `USER_COUNT`: Number of User objects to generate in response (default: 100)

## Monitoring and Profiling

### Built-in Monitoring

The benchmark script automatically collects:
- **pidstat** output (Linux only): CPU and memory usage over time
- **async-profiler** flamegraphs (when `-p` flag is used)

Results are saved to `scripts/results/` with timestamps.

### Manual Monitoring

While the benchmark is running, you can monitor with:

```bash
# Find the HTTP server PID
pgrep -f http-server.jar

# Monitor CPU and memory
pidstat -p <PID> 1

# Monitor with top
top -p <PID>
```

### Profiling with async-profiler

The script can automatically run async-profiler when you use the `-p` flag:

```bash
./benchmark.sh -p -d 120
```

This generates CPU flamegraphs in the results directory.

## Interpreting Results

### Key Metrics

1. **Throughput**: Requests per second (from wrk output)
2. **Latency**: Request latency distribution (from wrk output)
3. **CPU Usage**: From pidstat - compare CPU % between schedulers
4. **Memory**: RSS from pidstat - check for memory efficiency
5. **Context Switches**: Lower is generally better (check with pidstat -w)

### Comparing Schedulers

Run benchmarks with both schedulers under identical conditions:

```bash
# Custom scheduler
./benchmark.sh -s custom -d 60 -c 50 > custom_results.txt

# Default scheduler
./benchmark.sh -s default -d 60 -c 50 > default_results.txt
```

Compare:
- Throughput: Higher is better
- Latency (p50, p99): Lower is better
- CPU efficiency: Lower CPU % for same throughput is better
- Context switches: Lower is better for the custom scheduler

### Expected Behavior

The custom NettyScheduler should show:
- **Better cache locality**: Virtual threads tend to run on the same carrier thread as the event loop
- **Fewer context switches**: Less thread migration
- **Lower latency variance**: More predictable performance

Trade-offs:
- May show lower peak throughput if carrier threads are saturated
- Best when blocking operations are short to medium duration

## Troubleshooting

### Build Failures

If you get "invalid target release: 27", ensure you're using a Loom-enabled JDK:
```bash
java -version  # Should show Java 21+ with Loom support
```

### Connection Refused Errors

Ensure both servers are running:
```bash
# Check binary server
nc -zv localhost 9090

# Check HTTP server
curl http://localhost:8080/
```

### No jbang Command

Install jbang manually:
```bash
curl -Ls https://sh.jbang.dev | bash -s - app setup
export PATH="$HOME/.jbang/bin:$PATH"
```

### Docker Issues

If using Docker with `--cpuset-cpus`, ensure:
- You're on Linux (CPU affinity doesn't work on macOS/Windows Docker)
- Your Docker daemon has access to the specified CPUs

## Advanced Usage

### Custom JVM Arguments

Modify `JVM_ARGS` in the benchmark script:
```bash
export JVM_ARGS="-Xmx2g -Xms2g -XX:+UseG1GC"
./benchmark.sh
```

### Running Multiple Iterations

```bash
for i in {1..5}; do
  ./benchmark.sh -s custom -d 60 > results_custom_$i.txt
  sleep 10
done

for i in {1..5}; do
  ./benchmark.sh -s default -d 60 > results_default_$i.txt
  sleep 10
done
```

### Using perf (Linux)

For more detailed profiling:
```bash
# Find HTTP server PID
HTTP_PID=$(pgrep -f http-server.jar)

# Run perf
sudo perf record -g -p $HTTP_PID -- sleep 30
sudo perf report
```

## References

- Netty VirtualThread Scheduler: [Main README](../README.md)
- Quarkus Profiling Workshop: https://github.com/franz1981/quarkus-profiling-workshop
- Hyperfoil: https://hyperfoil.io/
- async-profiler: https://github.com/jvm-profiling-tools/async-profiler
