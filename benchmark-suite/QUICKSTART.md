# Quick Reference Guide

## Quick Start

```bash
# 1. Build the project
cd /path/to/Netty-VirtualThread-Scheduler
mvn clean install -DskipTests

# 2. Run smoke test (5 seconds, validates suite works)
cd benchmark-suite/scripts
./smoke-test.sh

# 3. Run a quick benchmark
./benchmark.sh

# 4. Compare schedulers
./compare.sh 60 20
```

## Common Commands

### Run smoke test (fast validation)
```bash
./smoke-test.sh
```

### Run with custom scheduler (default)
```bash
./benchmark.sh
```

### Run with default scheduler
```bash
./benchmark.sh -s default
```

### Run 60-second test with 50 connections
```bash
./benchmark.sh -d 60 -c 50
```

### Run with profiling enabled
```bash
./benchmark.sh -p
```

### Run with fixed rate (10k req/s)
```bash
./benchmark.sh -r 10000 -c 100
```

### Add backend latency (10ms think time)
```bash
./benchmark.sh -t 10
```

## Docker Quick Start

```bash
# Build
cd benchmark-suite
mvn clean package -DskipTests
docker compose -f docker/docker-compose.yml build

# Run with custom scheduler
docker compose -f docker/docker-compose.yml up binary-server http-server-custom

# In another terminal, run load test
jbang wrk@hyperfoil -c 20 -t 2 -d 30s http://localhost:8080/
```

## Environment Variables

### HTTP Server
- `HTTP_PORT=8080`
- `BACKEND_HOST=localhost`
- `BACKEND_PORT=9090`
- `SCHEDULER=custom` (or `default`)
- `EVENT_LOOPS=2`

### Binary Server
- `BINARY_PORT=9090`
- `THINK_TIME_MS=0`
- `USER_COUNT=100`

## Manual Server Starts

### Start Binary Server
```bash
java -jar target/binary-server.jar
```

### Start HTTP Server (Custom Scheduler)
```bash
java --enable-preview \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler \
  -DSCHEDULER=custom \
  -jar target/http-server.jar
```

### Start HTTP Server (Default Scheduler)
```bash
java --enable-preview \
  -Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler \
  -DSCHEDULER=default \
  -jar target/http-server.jar
```

## Test with curl

```bash
# Simple test
curl http://localhost:8080/

# Load test with curl
for i in {1..100}; do curl -s http://localhost:8080/ > /dev/null & done
wait
```

## Monitoring

### Monitor CPU/Memory (Linux)
```bash
pidstat -p $(pgrep -f http-server.jar) 1
```

### Monitor with top
```bash
top -p $(pgrep -f http-server.jar)
```

### Check server logs
```bash
# HTTP server logs
tail -f /path/to/http-server.log

# Binary server logs
tail -f /path/to/binary-server.log
```

## Results Location

All benchmark results are saved to:
```
benchmark-suite/scripts/results/
```

Files include:
- `TIMESTAMP_SCHEDULER_pidstat.txt` - CPU/memory stats
- `TIMESTAMP_SCHEDULER_cpu.html` - Flamegraph (if profiling enabled)
- Log files from compare.sh

## Troubleshooting

### Servers not starting
```bash
# Check ports
netstat -tuln | grep -E '8080|9090'

# Check if already running
pgrep -fa 'http-server|binary-server'

# Kill existing
pkill -f http-server
pkill -f binary-server
```

### Build issues
```bash
# Ensure correct Java version
java -version

# Clean rebuild
mvn clean install -DskipTests -U
```

### Performance issues
```bash
# Increase connections
./benchmark.sh -c 100

# Increase event loops
EVENT_LOOPS=4 ./benchmark.sh

# Disable GC logging
export JVM_ARGS="-Xmx2g -Xms2g -XX:+UseParallelGC"
./benchmark.sh
```
